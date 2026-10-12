package com.nytryx.burnai.api;

import com.nytryx.burnai.model.ExtractionResponse;
import com.nytryx.burnai.model.AggregateExtractionRequest;
import com.nytryx.burnai.model.ReportGenerationRequest;
import com.nytryx.burnai.model.ReportGenerationResponse;
import com.nytryx.burnai.service.IndicatorExtractionService;
import com.nytryx.burnai.service.ReportGenerationService;
import com.nytryx.burnai.service.RunLogBroker;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class AiDiagnosisApiController {
    private final IndicatorExtractionService extractionService;
    private final ReportGenerationService reportGenerationService;
    private final RunLogBroker logBroker;

    public AiDiagnosisApiController(
            IndicatorExtractionService extractionService,
            ReportGenerationService reportGenerationService,
            RunLogBroker logBroker
    ) {
        this.extractionService = extractionService;
        this.reportGenerationService = reportGenerationService;
        this.logBroker = logBroker;
    }

    @PostMapping("/indicators/extract")
    public ExtractionResponse extractIndicators(@Valid @RequestBody AggregateExtractionRequest request) {
        return extractionService.extract(request);
    }

    @GetMapping("/indicators/logs")
    public SseEmitter indicatorLogs(@RequestParam("requestId") String requestId, HttpServletResponse response) {
        prepareSseResponse(response);
        return logBroker.subscribe(requestId);
    }

    @PostMapping("/reports/generate")
    public ReportGenerationResponse generateReport(@Valid @RequestBody ReportGenerationRequest request) {
        return reportGenerationService.generate(request);
    }

    @GetMapping("/reports/logs")
    public SseEmitter logs(@RequestParam("requestId") String requestId, HttpServletResponse response) {
        prepareSseResponse(response);
        return logBroker.subscribe(requestId);
    }

    // 该函数用于流式传输
    private void prepareSseResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");// 禁止缓存
        response.setHeader("X-Accel-Buffering", "no");// 禁止代理服务器修改内容
        response.setHeader("Connection", "keep-alive");
    }

}
