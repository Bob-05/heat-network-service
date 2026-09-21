package ru.hackathon.heatnetworkservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.hackathon.heatnetworkservice.model.GeoObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервис расчёта стоимости варианта.
 *
 * По ТЗ:
 * - Стоимость обычного участка: L · cнов(ДУ) · Kгл.
 * - Стоимость специального участка: L · cнов(ДУ) · Kгл · Kспец.
 * - Стоимость камер — по наибольшему ДУ примыкающих участков (Таблица 3.2).
 * - Врезка в существующую камеру — 5 000 000 руб. за каждое примыкание.
 * - Штраф за неподключённую точку: 100 000 000 + 500 000 · G.
 * - Итоговая стоимость: construction_cost + unconnected_penalty.
 * - Score: 0,7 · (C / 25 000 000) + 0,3 · (L / 100).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostService {

    /** Стоимость врезки в существующую камеру (руб.) */
    private static final double TIE_IN_COST = 5_000_000.0;

    /** Штраф за неподключённую точку (базовая часть, руб.) */
    private static final double UNCONNECTED_BASE_PENALTY = 100_000_000.0;

    /** Штраф за неподключённую точку (за 1 т/ч расхода, руб.) */
    private static final double UNCONNECTED_FLOW_PENALTY = 500_000.0;

    /** Коэффициент для score (стоимость) */
    private static final double SCORE_COST_WEIGHT = 0.7;
    private static final double SCORE_COST_DIVISOR = 25_000_000.0;

    /** Коэффициент для score (длина) */
    private static final double SCORE_LENGTH_WEIGHT = 0.3;
    private static final double SCORE_LENGTH_DIVISOR = 100.0;

    /**
     * Результат расчёта стоимости одного варианта.
     */
    public static class CostResult {
        public double constructionCost;              // стоимость строительства
        public double chamberConstructionCost;       // стоимость новых камер
        public int existingChamberTieInCount;        // количество врезок в существующие камеры
        public double existingChamberTieInCost;      // стоимость врезок
        public double unconnectedPenalty;            // штраф за неподключённые точки
        public double calculatedCost;                // итоговая стоимость
        public double newNetworkLength;              // суммарная длина новых участков
        public double score;                         // итоговый показатель
        public List<String> unconnectedOksIds;       // ID неподключённых ОКС
    }

    /**
     * Рассчитывает стоимость варианта.
     *
     * @param segments          список рассчитанных участков
     * @param connectedOksIds   ID подключённых ОКС
     * @param allOks            все точки ОКС (для определения неподключённых)
     * @return результат расчёта
     */
    public CostResult calculate(
            List<FlowCalculationService.CalculatedSegment> segments,
            List<String> connectedOksIds,
            List<GeoObject> allOks
    ) {
        log.info("Начинаем расчёт стоимости: {} участков, {} подключённых ОКС",
                segments.size(), connectedOksIds.size());

        CostResult result = new CostResult();

        // 1. Стоимость участков (уже рассчитана в FlowCalculationService)
        double segmentsCost = 0;
        double totalLength = 0;
        for (FlowCalculationService.CalculatedSegment seg : segments) {
            segmentsCost += seg.cost;
            totalLength += seg.length;
        }
        result.newNetworkLength = totalLength;

        log.info("Стоимость участков: {} руб., длина: {} м", segmentsCost, totalLength);

        // 2. Врезки в существующие камеры
        // Считаем: для каждой ОКС — одна врезка (если маршрут заканчивается в камере)
        // Пока упрощённо: 1 врезка на каждый маршрут
        int tieInCount = connectedOksIds.size();
        double tieInCost = tieInCount * TIE_IN_COST;
        result.existingChamberTieInCount = tieInCount;
        result.existingChamberTieInCost = tieInCost;

        log.info("Врезок в существующие камеры: {}, стоимость: {} руб.",
                tieInCount, tieInCost);

        // 3. Новые камеры — пока 0 (все маршруты идут до существующих камер)
        result.chamberConstructionCost = 0;

        // 4. Итоговая стоимость строительства
        result.constructionCost = segmentsCost + result.chamberConstructionCost + tieInCost;

        log.info("Стоимость строительства: {} руб.", result.constructionCost);

        // 5. Штраф за неподключённые точки
        result.unconnectedOksIds = new ArrayList<>();
        double penalty = 0;
        for (GeoObject oks : allOks) {
            if (!connectedOksIds.contains(oks.getId())) {
                result.unconnectedOksIds.add(oks.getId());
                double flow = oks.getFlowTph() != null ? oks.getFlowTph() : 0;
                penalty += UNCONNECTED_BASE_PENALTY + UNCONNECTED_FLOW_PENALTY * flow;
                log.warn("Неподключённая ОКС {}: расход {} т/ч, штраф {} руб.",
                        oks.getId(), flow, UNCONNECTED_BASE_PENALTY + UNCONNECTED_FLOW_PENALTY * flow);
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
                String.format("%.4f", result.score));

        return result;
    }
}