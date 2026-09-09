package erd.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import erd.controller.dto.SequenceResponse;
import erd.service.model.ComponentLayer;
import erd.service.model.JavaComponent;
import erd.service.model.JavaMethod;
import erd.service.model.SourceModel;

@Service
public class SequenceDiagramService {

	private final SourceCodeAnalyzer sourceCodeAnalyzer;

	public SequenceDiagramService(SourceCodeAnalyzer sourceCodeAnalyzer) {
		this.sourceCodeAnalyzer = sourceCodeAnalyzer;
	}

	public SequenceResponse generate(String sourcePath, String controllerClass, String endpointMethod) {
		if (StringUtils.isBlank(controllerClass)) {
			throw new IllegalArgumentException("コントローラを選択してください。");
		}
		if (StringUtils.isBlank(endpointMethod)) {
			throw new IllegalArgumentException("エンドポイントを選択してください。");
		}

		SourceModel model = sourceCodeAnalyzer.analyze(sourcePath);
		JavaComponent controller = model.componentsByQualifiedName().get(controllerClass);
		if (controller == null || controller.layer() != ComponentLayer.CONTROLLER) {
			throw new IllegalArgumentException("指定したコントローラが見つかりません: " + controllerClass);
		}

		List<String> warnings = new ArrayList<>(model.warnings());
		JavaMethod entryMethod = controller.methods().stream()
				.filter(method -> method.name().equals(endpointMethod))
				.filter(method -> !method.httpMethods().isEmpty() || !method.paths().isEmpty())
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"指定したエンドポイントが見つかりません: " + endpointMethod));

		DiagramBuilder diagram = new DiagramBuilder(controller, model, warnings);
		diagram.appendEndpoint(entryMethod);
		return new SequenceResponse(diagram.build(), warnings.stream().distinct().toList());
	}
}
