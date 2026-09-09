package erd.controller.dto;

import java.util.List;

public record ErdResponse(
        String mermaid,
        List<String> tables,
        List<String> warnings
) {
}
