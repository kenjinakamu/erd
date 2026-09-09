package erd.controller.dto;

public record SequenceGenerateRequest(String sourcePath, String controllerClass, String endpointMethod) {
}
