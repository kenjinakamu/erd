package erd.controller.dto;

import java.util.List;

public record ControllerInfo(String qualifiedName, String className, String filePath, List<EndpointInfo> endpoints) {
}
