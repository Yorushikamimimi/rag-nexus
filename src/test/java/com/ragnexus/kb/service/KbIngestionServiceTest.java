package com.ragnexus.kb.service;

import com.ragnexus.kb.domain.dto.KbIngestRequest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KbIngestionServiceTest {

    @Test
    void ingestFromFileRejectsPdfWithoutExtractableTextWithoutWritingToVectorStore()
            throws IOException {
        VectorStore vectorStore = mock(VectorStore.class);
        KbIngestionService service = new KbIngestionService(vectorStore);
        byte[] blankPdf;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            blankPdf = output.toByteArray();
        }
        MockMultipartFile file =
                new MockMultipartFile("file", "scan.pdf", "application/pdf", blankPdf);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.ingestFromFile(file));
        assertTrue(exception.getMessage().contains("已有向量保持不变"));

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void ingestStoresChunksWhenTextIsLongEnough() {
        VectorStore vectorStore = mock(VectorStore.class);
        KbIngestionService service = new KbIngestionService(vectorStore);
        String content = "Synthetic routing marker cedar-731. ".repeat(12);
        KbIngestRequest request = KbIngestRequest.builder()
                .docName("synthetic.txt")
                .content(content)
                .build();

        int chunksCreated = service.ingest(request);

        assertTrue(chunksCreated > 0);
        verify(vectorStore).add(anyList());
    }
}
