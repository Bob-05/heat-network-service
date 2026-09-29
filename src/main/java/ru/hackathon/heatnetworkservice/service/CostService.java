package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CostService {

    private static final double UNCONNECTED_BASE_PENALTY = 100_000_000.0;
    private static final double UNCONNECTED_FLOW_PENALTY = 500_000.0;
    private static final double SCORE_COST_WEIGHT = 0.7;
    private static final double SCORE_COST_DIVISOR = 25_000_000.0;
    private static final double SCORE_LENGTH_WEIGHT = 0.3;
    private static final double SCORE_LENGTH_DIVISOR = 100.0;

    private final TieInService tieInService;

    public static class CostResult {
        public double constructionCost;
        public double chamberConstructionCost;
        public int existingChamberTieInCount;
        public double existingChamberTieInCost;
        public double unconnectedPenalty;
        public double calculatedCost;
        public double newNetworkLength;
        public double score;
        public List<String> unconnectedOksIds;
        public List<TieInService.TieInResult> tieIns;
    }

    public CostResult calculate(
            List<FlowCalculationService.CalculatedSegment> segments,
            List<String> connectedOksIds,
            List<GeoObject> allOks,
            List<RoutingService.Route> routes,
            List<GeoObject> chambers,
            List<GeoObject> existingNetworks,
            java.util.Map<String, Integer> oksDiameters
    ) {
        log.info("Начинаем расчёт стоимости: {} участков, {} маршрутов",
                segments.size(), routes.size());

        CostResult result = new CostResult();

        // 1. Стоимость участков (дедупликация уже сделана в FlowCalculationService).
        double segmentsCost = 0;
        double totalLength = 0;
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            segmentsCost += seg.cost;
            totalLength += seg.length;
        }
        result.newNetworkLength = totalLength;

        // 2. Врезки, камеры и junction-камеры.
        //    segments передаётся, т.к. TieInService мутирует node-ID сегментов
        //    при обнаружении junction-камер (ТЗ п.2.1).
        List<TieInService.TieInResult> tieIns = tieInService.determineTieIns(
                routes, segments, chambers, existingNetworks, oksDiameters);
        result.tieIns = tieIns;

        int tieInCount = 0;
        double tieInCost = 0;
        double chamberCost = 0;

        for (TieInService.TieInResult tieIn : tieIns) {
            if (tieIn.useExistingChamber) {
                tieInCount += Math.max(1, tieIn.existingChamberTieInCount);
                tieInCost += tieIn.tieInCost;
            } else {
                chamberCost += tieIn.newChamberCost;
            }
        }

        result.existingChamberTieInCount = tieInCount;
        result.existingChamberTieInCost = tieInCost;
        result.chamberConstructionCost = chamberCost;

        result.constructionCost = segmentsCost + chamberCost + tieInCost;

        log.info("Стоимость участков: {} руб., длина: {} м",
                Math.round(segmentsCost), Math.round(totalLength));
        log.info("Врезок в существующие камеры: {}, стоимость: {} руб.",
                tieInCount, Math.round(tieInCost));
        log.info("Новых камер: {}, стоимость: {} руб.",
                tieIns.size() - tieInCount, Math.round(chamberCost));
        log.info("Стоимость строительства: {} руб.", Math.round(result.constructionCost));

        // 3. Штраф за неподключённые точки
        result.unconnectedOksIds = new ArrayList<>();
        Set<String> connectedSet = new HashSet<>(connectedOksIds);
        double penalty = 0;

        for (GeoObject oks : allOks) {
            if (!connectedSet.contains(oks.getId())) {
                result.unconnectedOksIds.add(oks.getId());
                double flow = oks.getFlowTph() != null ? oks.getFlowTph() : 0;
                penalty += UNCONNECTED_BASE_PENALTY + UNCONNECTED_FLOW_PENALTY * flow;
                log.warn("Неподключённая ОКС {}: расход {} т/ч", oks.getId(), flow);
            }
        }
        result.unconnectedPenalty = penalty;

        // 4. Итоговая стоимость
        result.calculatedCost = result.constructionCost + result.unconnectedPenalty;

        // 5. Score
        result.score = SCORE_COST_WEIGHT * (result.calculatedCost / SCORE_COST_DIVISOR)
                + SCORE_LENGTH_WEIGHT * (result.newNetworkLength / SCORE_LENGTH_DIVISOR);

        log.info("Итог: construction_cost={}, chamber_cost={}, tie_in_cost={}, penalty={}, calculated_cost={}, score={}",
                Math.round(result.constructionCost),
                Math.round(result.chamberConstructionCost),
                Math.round(result.existingChamberTieInCost),
                Math.round(result.unconnectedPenalty),
                Math.round(result.calculatedCost),
                String.format(java.util.Locale.US, "%.4f", result.score));

        return result;
    }
}