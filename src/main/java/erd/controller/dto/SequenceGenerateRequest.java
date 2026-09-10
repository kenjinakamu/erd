package erd.controller.dto;

import java.util.List;

public record SequenceGenerateRequest(String sourcePath, String controllerClass, String endpointMethod,
                                      List<String> excludedClasses) {
}
