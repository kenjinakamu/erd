package erd.controller.dto;

public record FlowchartGenerateRequest(String sourcePath, String controllerClass, String endpointMethod) {
}
