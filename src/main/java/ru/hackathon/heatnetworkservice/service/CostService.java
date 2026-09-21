package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CostService {

    private static final double TIE_IN_COST = 5_000_000.0;
    private static final double UNCONNECTED_BASE_PENALTY = 100_000_000.0;
    private static final double UNCONNECTED_FLOW_PENALTY = 500_000.0;
    private static final double SCORE_COST_WEIGHT = 0.7;
    private static final double SCORE_COST_DIVISOR = 25_000_000.0;
    private static final double SCORE_LENGTH_WEIGHT = 0.3;
    private static final double SCORE_LENGTH_DIVISOR = 100.0;

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
    }

    public CostResult calculate(
            List<FlowCalculationService.CalculatedSegment> segments,
            List<String> connectedOksIds,
            List<GeoObject> allOks
    ) {
        log.info("Начинаем расчёт стоимости: {} участков, {} подключённых ОКС",
                segments.size(), connectedOksIds.size());

        CostResult result = new CostResult();

        // 1. Стоимость участков
        double segmentsCost = 0;
        double totalLength = 0;
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            segmentsCost += seg.cost;
            totalLength += seg.length;
        }
        result.newNetworkLength = totalLength;

        // 2. Врезки в существующие камеры
        // Считаем только те маршруты, что заканчиваются в камере.
        // Для маршрутов, заканчивающихся в точке сети — определит TieInService (позже).
        int tieInCount = 0;
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            if (seg.routeEndIsChamber) {
                // Считаем одну врезку на маршрут, не на участок.
                // Чтобы не дублировать — считаем уникальные (oksId + routeEndNodeId).
                // Логика ниже: в цикле по segments мы это обработаем через Set.
            }
        }

        // Правильный подсчёт уникальных врезок
        java.util.Set<String> tieInKeys = new java.util.HashSet<>();
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            if (seg.routeEndIsChamber) {
                String key = seg.oksId + "|" + seg.routeEndNodeId;
                tieInKeys.add(key);
            }
        }
        tieInCount = tieInKeys.size();

        double tieInCost = tieInCount * TIE_IN_COST;
        result.existingChamberTieInCount = tieInCount;
        result.existingChamberTieInCost = tieInCost;

        // 3. Новые камеры — пока 0 (логика в TieInService, позже)
        result.chamberConstructionCost = 0;

        // 4. Итоговая стоимость строительства
        result.constructionCost = segmentsCost + result.chamberConstructionCost + tieInCost;

        log.info("Стоимость участков: {} руб., длина: {} м",
                Math.round(segmentsCost), Math.round(totalLength));
        log.info("Врезок в существующие камеры: {}, стоимость: {} руб.",
                tieInCount, Math.round(tieInCost));
        log.info("Стоимость строительства: {} руб.", Math.round(result.constructionCost));

        // 5. Штраф за неподключённые точки
        result.unconnectedOksIds = new ArrayList<>();
        double penalty = 0;
        for (GeoObject oks : allOks) {
            if (!connectedOksIds.contains(oks.getId())) {
                result.unconnectedOksIds.add(oks.getId());
                double flow = oks.getFlowTph() != null ? oks.getFlowTph() : 0;
                penalty += UNCONNECTED_BASE_PENALTY + UNCONNECTED_FLOW_PENALTY * flow;
                log.warn("Неподключённая ОКС {}: расход {} т/ч", oks.getId(), flow);
            }
        }
        result.unconnectedPenalty = penalty;

        // 6. Итоговая стоимость
        result.calculatedCost = result.constructionCost + result.unconnectedPenalty;

        // 7. Score
        result.score = SCORE_COST_WEIGHT * (result.calculatedCost / SCORE_COST_DIVISOR)
                + SCORE_LENGTH_WEIGHT * (result.newNetworkLength / SCORE_LENGTH_DIVISOR);

        log.info("Итог: construction_cost={}, penalty={}, calculated_cost={}, score={}",
                Math.round(result.constructionCost),
                Math.round(result.unconnectedPenalty),
                Math.round(result.calculatedCost),
                String.format(java.util.Locale.US, "%.4f", result.score));

        return result;
    }
}