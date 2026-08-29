package com.smartsupply.agent.rag;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    public String parse(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".txt")) {
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (InputStream is = file.getInputStream()) {
            String text = tika.parseToString(is);
            return text == null ? "" : text;
        } catch (Exception e) {
            // Tika 解析失败则退化为文本
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
