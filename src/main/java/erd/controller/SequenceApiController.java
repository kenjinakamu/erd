package erd.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import erd.controller.dto.SequenceGenerateRequest;
import erd.controller.dto.SequenceResponse;
import erd.controller.dto.SourceScanRequest;
import erd.controller.dto.SourceScanResponse;
import erd.service.SequenceDiagramService;
import erd.service.SourceCodeAnalyzer;

@RestController
@RequestMapping("/api/sequence")
public class SequenceApiController {

	private final SourceCodeAnalyzer sourceCodeAnalyzer;
	private final SequenceDiagramService sequenceDiagramService;

	public SequenceApiController(SourceCodeAnalyzer sourceCodeAnalyzer,
								 SequenceDiagramService sequenceDiagramService) {
		this.sourceCodeAnalyzer = sourceCodeAnalyzer;
		this.sequenceDiagramService = sequenceDiagramService;
	}

	/**
	 * Javaソースのパスからコントローラ一覧を取得
	 */
	@PostMapping("/controllers")
	public SourceScanResponse controllers(@RequestBody SourceScanRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Javaソースのパスを入力してください。");
		}
		return sourceCodeAnalyzer.scanControllers(request.sourcePath());
	}

	@PostMapping("/generate")
	public SequenceResponse generate(@RequestBody SequenceGenerateRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Javaソースのパス、コントローラ、エンドポイントを指定してください。");
		}
		return sequenceDiagramService.generate(request.sourcePath(), request.controllerClass(),
				request.endpointMethod(), request.excludedClasses());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("message", "シーケンス図の生成に失敗しました: " + e.getMessage()));
	}
}
