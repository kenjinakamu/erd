package erd.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import erd.controller.dto.FlowchartGenerateRequest;
import erd.controller.dto.FlowchartResponse;
import erd.controller.dto.SourceScanRequest;
import erd.controller.dto.SourceScanResponse;
import erd.service.FlowchartService;
import erd.service.SourceCodeAnalyzer;

@RestController
@RequestMapping("/api/flowchart")
public class FlowchartApiController {

    private final SourceCodeAnalyzer sourceCodeAnalyzer;
    private final FlowchartService flowchartService;

    public FlowchartApiController(SourceCodeAnalyzer sourceCodeAnalyzer, FlowchartService flowchartService) {
        this.sourceCodeAnalyzer = sourceCodeAnalyzer;
        this.flowchartService = flowchartService;
    }

    /** Javaソースのパスからコントローラ一覧を取得 */
    @PostMapping("/controllers")
    public SourceScanResponse controllers(@RequestBody SourceScanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Javaソースのパスを入力してください。");
        }
        return sourceCodeAnalyzer.scanControllers(request.sourcePath());
    }

    @PostMapping("/generate")
    public FlowchartResponse generate(@RequestBody FlowchartGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Javaソースのパス、コントローラ、エンドポイントを指定してください。");
        }
        return flowchartService.generate(request.sourcePath(), request.controllerClass(), request.endpointMethod());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", errorDetail(e)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "フローチャートの生成に失敗しました: " + errorDetail(e)));
    }

    private String errorDetail(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }
}
