package erd.controller.dto;

import java.util.List;

public record EndpointInfo(String methodName, List<String> httpMethods, List<String> paths) {
}
