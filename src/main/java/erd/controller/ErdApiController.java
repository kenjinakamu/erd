package erd.controller;

import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import erd.controller.dto.ErdRequest;
import erd.controller.dto.ErdResponse;
import erd.service.ErdService;

/**
 * ER図APIコントローラ
 */
@RestController
@RequestMapping("/api/erd")
public class ErdApiController {

	private final ErdService erdService;

	public ErdApiController(ErdService erdService) {
		this.erdService = erdService;
	}

	/**
	 * SQLからER図を生成
	 */
	@PostMapping("/generate")
	public ErdResponse generate(@RequestBody ErdRequest request) {
		if (Objects.isNull(request)) {
			throw new IllegalArgumentException("SQLを入力してください。");
		}
		String sql = request.sql();
		if (StringUtils.isBlank(sql)) {
			throw new IllegalArgumentException("SQLを入力してください。");
		}
		
		ErdResponse response =erdService.generate(sql); 
		return response;
	}
	
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("message", "ER図の生成に失敗しました: " + e.getMessage()));
	}
}
