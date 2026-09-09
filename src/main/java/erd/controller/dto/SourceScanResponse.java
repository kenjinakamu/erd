package erd.controller.dto;

import java.util.List;

public record SourceScanResponse(List<ControllerInfo> controllers, List<String> warnings) {
}