package com.aguaviva.contracts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class OpenApiYamlSupport {

    private OpenApiYamlSupport() {}

    static Map<String, Object> load(Path yamlPath) throws IOException {
        String yamlContent = Files.readString(yamlPath);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object parsed = yaml.load(yamlContent);
        return asMap(parsed, yamlPath.toString());
    }

    static Map<String, Object> requiredMap(Map<String, Object> parent, String key, String context) {
        Object value = parent.get(key);
        if (value == null) {
            throw new IllegalStateException("Campo obrigatorio ausente em " + context + ": " + key);
        }
        return asMap(value, context + "." + key);
    }

    static List<Object> requiredList(Map<String, Object> parent, String key, String context) {
        Object value = parent.get(key);
        if (value == null) {
            throw new IllegalStateException("Campo obrigatorio ausente em " + context + ": " + key);
        }
        return asList(value, context + "." + key);
    }

    static Map<String, Object> asMap(Object value, String context) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Esperado objeto/map em " + context);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("Chave nao textual em " + context);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static List<Object> asList(Object value, String context) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalStateException("Esperado lista em " + context);
        }
        return new ArrayList<>(raw);
    }
}
