package dev.autopulse.diagnostic;

import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Tiny versioned original factual summaries; no runtime scraping or guessed fallback. */
@Component
public class DiagnosticCatalog {
    public record Definition(String verification,String description,String sourceUrl,String version,String license,String scope,String sourceTitle) {}
    private final Map<String,Definition> entries;
    public DiagnosticCatalog(ObjectMapper mapper) throws IOException {
        try(var input=new ClassPathResource("diagnostic/catalog-v1.json").getInputStream()) {
            var root=mapper.readTree(input); var values=new HashMap<String,Definition>();
            for(var entry:root.get("entries")) {
                String code=entry.get("code").asText(); if(!code.matches("P0[0-9A-F]{3}") || values.containsKey(code)) throw new IllegalStateException("Invalid generic catalog");
                values.put(code,new Definition("VERIFIED",entry.get("description").asText(),entry.get("sourceUrl").asText(),root.get("version").asText(),root.get("license").asText(),root.get("scope").asText(),entry.get("sourceTitle").asText()));
            }
            entries=Map.copyOf(values);
        }
    }
    public Definition lookup(String code) { return entries.getOrDefault(code,new Definition("UNKNOWN",null,null,null,null,"No verified definition; code evidence remains visible.",null)); }
}
