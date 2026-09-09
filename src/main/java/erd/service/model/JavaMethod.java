package erd.service.model;

import java.util.List;
import java.util.Map;

import com.github.javaparser.ast.body.MethodDeclaration;

public record JavaMethod(
        String name,
        String javadocSummary,
        MethodDeclaration declaration,
        Map<String, String> variableTypes,
        List<String> httpMethods,
        List<String> paths) {
}
