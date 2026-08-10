package com.internalops.productimage;

import com.internalops.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProductImageController {
    private final ProductImageService service;

    public ProductImageController(ProductImageService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}/images")
    public ApiResponse<List<ProductImageView>> list(@PathVariable long productId) {
        return ApiResponse.ok(service.list(productId));
    }

    @PostMapping(path = "/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<ProductImageView>> upload(@PathVariable long productId, @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(service.upload(productId, files));
    }

    @GetMapping("/product-images/{imageId}/content")
    public ResponseEntity<byte[]> content(@PathVariable long imageId) {
        ProductImageService.Content content = service.content(imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .header("Cache-Control", "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @PutMapping("/products/{productId}/images/order")
    public ApiResponse<List<ProductImageView>> reorder(@PathVariable long productId, @RequestBody ProductImageOrderRequest request) {
        return ApiResponse.ok(service.reorder(productId, request));
    }

    @PutMapping("/products/{productId}/images/{imageId}/primary")
    public ApiResponse<List<ProductImageView>> makePrimary(@PathVariable long productId, @PathVariable long imageId) {
        return ApiResponse.ok(service.makePrimary(productId, imageId));
    }

    @DeleteMapping("/products/{productId}/images/{imageId}")
    public ApiResponse<List<ProductImageView>> delete(@PathVariable long productId, @PathVariable long imageId) {
        return ApiResponse.ok(service.delete(productId, imageId));
    }

    @ExceptionHandler(ProductImageService.NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(ProductImageService.NotFoundException exception) {
        return ResponseEntity.status(404).body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(ProductImageService.ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(ProductImageService.ConflictException exception) {
        return ResponseEntity.status(409).body(new ApiResponse<>(false, null, exception.getMessage()));
    }

    @ExceptionHandler(ProductImageService.InternalException.class)
    public ResponseEntity<ApiResponse<Void>> internal(ProductImageService.InternalException exception) {
        return ResponseEntity.status(500).body(new ApiResponse<>(false, null, exception.getMessage()));
    }
}
