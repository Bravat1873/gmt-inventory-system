package com.internalops.productimage;

import com.internalops.auth.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductImageService {
    static final int MAX_IMAGES = 10;
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);

    private final JdbcTemplate jdbc;
    private final ProductImageStorage storage;

    public ProductImageService(JdbcTemplate jdbc, ProductImageStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public List<ProductImageView> list(long productId) {
        CurrentUser.required();
        productExists(productId);
        return images(productId);
    }

    @Transactional
    public List<ProductImageView> upload(long productId, List<MultipartFile> files) {
        CurrentUser user = CurrentUser.required();
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Please choose an image to upload");
        List<Upload> uploads = files.stream().map(this::upload).toList();
        lockProduct(productId);
        int currentCount = imageCount(productId);
        if (currentCount + uploads.size() > MAX_IMAGES) throw new IllegalArgumentException("A product can contain at most 10 images");

        List<ProductImageView> result = new ArrayList<>();
        List<String> storedKeys = new ArrayList<>();
        try {
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                String key = productId + "/" + UUID.randomUUID() + extension(upload.contentType());
                storage.store(productId, key, upload.content());
                storedKeys.add(key);
                long id = insert(productId, key, upload.filename(), upload.contentType(), upload.content().length,
                        currentCount == 0 && index == 0, currentCount + index, user.username());
                result.add(image(id));
            }
        } catch (IOException exception) {
            storedKeys.forEach(this::deleteQuietly);
            throw new IllegalStateException("Unable to store product image", exception);
        } catch (RuntimeException exception) {
            storedKeys.forEach(this::deleteQuietly);
            throw exception;
        }
        return result;
    }

    public Content content(long imageId) {
        CurrentUser.required();
        ImageRecord record = imageRecord(imageId);
        try {
            ProductImageFile file = storage.read(record.storageKey());
            return new Content(file.content(), record.contentType());
        } catch (IOException exception) {
            log.warn("Product image content is missing for image {}", imageId, exception);
            throw new NotFoundException("Product image content is unavailable", exception);
        }
    }

    @Transactional
    public List<ProductImageView> reorder(long productId, ProductImageOrderRequest request) {
        CurrentUser.required();
        lockProduct(productId);
        List<ProductImageView> current = images(productId);
        List<Long> requested = request == null ? null : request.imageIds();
        if (requested == null || requested.size() != current.size() || new HashSet<>(requested).size() != requested.size()) {
            throw new IllegalArgumentException("Image order must contain every current image exactly once");
        }
        Set<Long> currentIds = current.stream().map(ProductImageView::id).collect(java.util.stream.Collectors.toSet());
        if (!currentIds.equals(new HashSet<>(requested))) throw new IllegalArgumentException("Image order must contain every current image exactly once");

        for (int index = 0; index < requested.size(); index++) {
            jdbc.update("UPDATE product_image SET sort_order=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND product_id=?",
                    -(index + current.size() + 1), requested.get(index), productId);
        }
        for (int index = 0; index < requested.size(); index++) {
            jdbc.update("UPDATE product_image SET sort_order=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND product_id=?",
                    index, requested.get(index), productId);
        }
        Integer primaryCount = jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=? AND is_primary=TRUE", Integer.class, productId);
        if (primaryCount != null && primaryCount == 0 && !requested.isEmpty()) setPrimary(productId, requested.get(0));
        return images(productId);
    }

    @Transactional
    public List<ProductImageView> makePrimary(long productId, long imageId) {
        CurrentUser.required();
        lockProduct(productId);
        if (!imageBelongsToProduct(productId, imageId)) throw new NotFoundException("Product image was not found");
        setPrimary(productId, imageId);
        return images(productId);
    }

    @Transactional
    public List<ProductImageView> delete(long productId, long imageId) {
        CurrentUser.required();
        lockProduct(productId);
        ImageRecord target = imageRecordForProduct(productId, imageId);
        jdbc.update("DELETE FROM product_image WHERE id=? AND product_id=?", imageId, productId);
        if (target.primary()) {
            List<Long> remaining = jdbc.queryForList("SELECT id FROM product_image WHERE product_id=? ORDER BY sort_order,id", Long.class, productId);
            if (!remaining.isEmpty()) setPrimary(productId, remaining.get(0));
        }
        try {
            storage.delete(target.storageKey());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to delete product image content", exception);
        }
        return images(productId);
    }

    private Upload upload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Image file cannot be empty");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("Each image must be 5MB or smaller");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Each image must be 5MB or smaller");
            return new Upload(safeFilename(file.getOriginalFilename()), detectedType(bytes), bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read uploaded image", exception);
        }
    }

    private String detectedType(byte[] bytes) {
        if (startsWith(bytes, new int[]{0xFF, 0xD8, 0xFF})) return "image/jpeg";
        if (startsWith(bytes, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) return "image/png";
        if (bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 12).equals("WEBP")) return "image/webp";
        throw new IllegalArgumentException("Only JPG, PNG, and WebP images are supported");
    }

    private boolean startsWith(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) if ((bytes[index] & 0xFF) != signature[index]) return false;
        return true;
    }

    private String ascii(byte[] bytes, int start, int end) {
        return new String(bytes, start, end - start, StandardCharsets.US_ASCII);
    }

    private void lockProduct(long productId) {
        List<Long> products = jdbc.queryForList("SELECT id FROM sku WHERE id=? FOR UPDATE", Long.class, productId);
        if (products.isEmpty()) throw new NotFoundException("Product was not found");
    }

    private void productExists(long productId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=?", Integer.class, productId);
        if (count == null || count == 0) throw new NotFoundException("Product was not found");
    }

    private int imageCount(long productId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?", Integer.class, productId);
        return count == null ? 0 : count;
    }

    private long insert(long productId, String key, String filename, String contentType, long size, boolean primary, int sortOrder, String username) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO product_image(product_id,storage_key,original_filename,content_type,file_size,is_primary,sort_order,uploaded_by) VALUES(?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, productId);
            statement.setString(2, key);
            statement.setString(3, filename);
            statement.setString(4, contentType);
            statement.setLong(5, size);
            statement.setBoolean(6, primary);
            statement.setInt(7, sortOrder);
            statement.setString(8, username);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Image metadata was not created");
        return keys.getKey().longValue();
    }

    private void setPrimary(long productId, long imageId) {
        jdbc.update("UPDATE product_image SET is_primary=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE product_id=?", productId);
        int updated = jdbc.update("UPDATE product_image SET is_primary=TRUE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND product_id=?", imageId, productId);
        if (updated != 1) throw new NotFoundException("Product image was not found");
    }

    private List<ProductImageView> images(long productId) {
        return jdbc.query("SELECT id,product_id,original_filename,content_type,file_size,is_primary,sort_order FROM product_image WHERE product_id=? ORDER BY sort_order,id",
                (rs, row) -> new ProductImageView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("original_filename"),
                        rs.getString("content_type"), rs.getLong("file_size"), rs.getBoolean("is_primary"), rs.getInt("sort_order"),
                        "/api/product-images/" + rs.getLong("id") + "/content"), productId);
    }

    private ProductImageView image(long id) {
        return jdbc.queryForObject("SELECT id,product_id,original_filename,content_type,file_size,is_primary,sort_order FROM product_image WHERE id=?",
                (rs, row) -> new ProductImageView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("original_filename"),
                        rs.getString("content_type"), rs.getLong("file_size"), rs.getBoolean("is_primary"), rs.getInt("sort_order"),
                        "/api/product-images/" + rs.getLong("id") + "/content"), id);
    }

    private ImageRecord imageRecord(long imageId) {
        List<ImageRecord> records = jdbc.query("SELECT id,product_id,storage_key,content_type,is_primary FROM product_image WHERE id=?",
                (rs, row) -> new ImageRecord(rs.getLong("id"), rs.getLong("product_id"), rs.getString("storage_key"), rs.getString("content_type"), rs.getBoolean("is_primary")), imageId);
        if (records.isEmpty()) throw new NotFoundException("Product image was not found");
        return records.get(0);
    }

    private ImageRecord imageRecordForProduct(long productId, long imageId) {
        ImageRecord record = imageRecord(imageId);
        if (record.productId() != productId) throw new NotFoundException("Product image was not found");
        return record;
    }

    private boolean imageBelongsToProduct(long productId, long imageId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE id=? AND product_id=?", Integer.class, imageId, productId);
        return count != null && count == 1;
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "image";
        return filename.replace('\\', '_').replace('/', '_');
    }

    private void deleteQuietly(String key) {
        try { storage.delete(key); } catch (IOException ignored) { }
    }

    private record Upload(String filename, String contentType, byte[] content) { }
    private record ImageRecord(long id, long productId, String storageKey, String contentType, boolean primary) { }
    public record Content(byte[] bytes, String contentType) { }
    public static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
        NotFoundException(String message, Throwable cause) { super(message, cause); }
    }
}
