package erd.controller.dto;

import java.util.List;

public record FlowchartResponse(String mermaid, List<String> warnings) {
}
