package ru.hackathon.heatnetworkservice;

import org.junit.jupiter.api.Test;
import ru.hackathon.heatnetworkservice.service.FlowCalculationService;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowCalculationServiceTest {

    private final FlowCalculationService service = new FlowCalculationService();

    @Test
    void selectDiameter_byFlowAndLength() {
        // 3.5 т/ч, 181 м → ДУ 50
        assertEquals(50, service.selectDiameter(3.5, 181));

        // 3.6 т/ч, 181 м → ДУ 65 (50 не проходит по расходу)
        assertEquals(65, service.selectDiameter(3.6, 181));

        // 3.5 т/ч, 182 м → ДУ 65 (50 не проходит по длине)
        assertEquals(65, service.selectDiameter(3.5, 182));

        // 10 т/ч, 300 м → ДУ 80
        assertEquals(80, service.selectDiameter(10, 300));

        // 100 т/ч, 1000 м → ДУ 250 (152.3 >= 100, 1379 >= 1000)
        assertEquals(250, service.selectDiameter(100, 1000));
    }

    @Test
    void getCostPerMeter() {
        assertEquals(74023, service.getCostPerMeter(50));
        assertEquals(89748, service.getCostPerMeter(100));
        assertEquals(224137, service.getCostPerMeter(500));
        assertEquals(683417, service.getCostPerMeter(1400));
    }
}