package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.context.DtoMapper;
import io.github.jbellis.brokk.context.FragmentDtos.HistoryDto;
import io.github.jbellis.brokk.context.FrozenFragment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class HistoryIo {
    private static final Logger logger = LogManager.getLogger(HistoryIo.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.CLOSE_CLOSEABLE, false);

    private HistoryIo() {}

    public static void writeZip(ContextHistory ch, Path zip) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            // Write history.json
            HistoryDto historyDto = DtoMapper.toHistoryDto(ch);
            byte[] jsonBytes = objectMapper.writeValueAsBytes(historyDto);
            ZipEntry jsonEntry = new ZipEntry("history.json");
            zos.putNextEntry(jsonEntry);
            zos.write(jsonBytes);
            zos.closeEntry();

            // Write images
            ch.getHistory().stream()
                .flatMap(ctx -> ctx.allFragments())
                .filter(f -> !f.isText() && f instanceof FrozenFragment)
                .forEach(f -> {
                    FrozenFragment ff = (FrozenFragment) f;
                    byte[] imageBytes = ff.imageBytesContent();
                    if (imageBytes != null && imageBytes.length > 0) {
                        try {
                            ZipEntry entry = new ZipEntry("images/" + f.id() + ".png");
                            entry.setMethod(ZipEntry.STORED);
                            entry.setSize(imageBytes.length);
                            entry.setCompressedSize(imageBytes.length);
                            CRC32 crc = new CRC32();
                            crc.update(imageBytes);
                            entry.setCrc(crc.getValue());

                            zos.putNextEntry(entry);
                            zos.write(imageBytes);
                            zos.closeEntry();
                        } catch (IOException e) {
                            logger.error("Failed to write image for fragment {} to zip", f.id(), e);
                            // Decide if we should throw UncheckedIOException or just log and continue
                            // For now, logging and continuing to save the rest of the history.
                        }
                    }
                });
        } catch (Exception e) {
            logger.error("Failed to write history zip file: {}", zip, e);
            throw new IOException("Failed to write history zip file: " + zip, e);
        }
    }

    public static ContextHistory readZip(Path zip, IContextManager mgr) throws IOException {
        HistoryDto dto = null;
        Map<Integer, byte[]> images = new HashMap<>();

        if (!Files.exists(zip)) {
            logger.warn("History zip file not found: {}. Returning empty history.", zip);
            return new ContextHistory(); // Or perhaps a new history with initial context from mgr
        }

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("history.json")) {
                    // Read the entry's content into a byte array first to prevent ObjectMapper from closing zis
                    byte[] jsonData = zis.readAllBytes();
                    dto = objectMapper.readValue(jsonData, HistoryDto.class);
                } else if (entry.getName().startsWith("images/")) {
                    try {
                        int id = idFromName(entry.getName());
                        images.put(id, zis.readAllBytes());
                    } catch (NumberFormatException e) {
                        logger.warn("Could not parse fragment ID from image entry name: {}", entry.getName(), e);
                    }
                }
                zis.closeEntry();
            }
        }

        if (dto == null) {
            // This could happen if the zip is empty or history.json is missing
            logger.warn("history.json not found in zip: {}. Returning empty history.", zip);
            return new ContextHistory(); // Or handle as an error requiring fresh history
        }
        return DtoMapper.fromHistoryDto(dto, mgr, images);
    }

    private static int idFromName(String entryName) {
        // entryName is like "images/123.png"
        String nameWithoutPrefix = entryName.substring("images/".length());
        String idStr = nameWithoutPrefix.substring(0, nameWithoutPrefix.lastIndexOf('.'));
        return Integer.parseInt(idStr);
    }
}
