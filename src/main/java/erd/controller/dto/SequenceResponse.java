package erd.controller.dto;

import java.util.List;

public record SequenceResponse(String mermaid, List<String> warnings) {
}
