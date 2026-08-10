# 产品图片图库实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为产品管理增加基于本地 Docker 持久化卷的一张主图加多张详情图能力，并在产品表格中显示可点击的主图缩略图。

**Architecture:** 后端以 `ProductImageStorage` 隔离文件存储，以 `ProductImageService` 维护数量、主图和排序不变量，MySQL 仅保存元数据。前端使用独立的图片选择器和图库组件；新增产品先保存产品资料取得 ID，再批量上传已选择图片。

**Tech Stack:** Java 21、Spring Boot 3.3、JdbcTemplate、Flyway、Vue 3、TypeScript、Vitest、Docker Compose、本地命名卷。

## Global Constraints

- 每个产品最多 10 张图片，其中有图时必须且只能有一张主图。
- 单张图片最大 5MB，仅接受 JPEG、PNG、WebP，并以服务端文件签名检测结果为准。
- 所有已登录用户均可查看、上传、删除、排序和设置主图。
- 图片访问必须经过后端登录鉴权，不暴露宿主机真实路径。
- 新增产品先保存资料再上传图片；图片部分失败不回滚产品，失败项可重试。
- 本期只使用本地 Docker 持久化卷，但存储接口必须允许未来替换为 MinIO。
- 产品列表保持紧凑表格布局，主图缩略图约 56×56 像素。

---

## 文件结构

新增后端文件按职责拆分：

- `V26__product_image_gallery.sql`：产品图片元数据表与约束。
- `ProductImageStorage.java` / `LocalProductImageStorage.java`：文件写入、读取、删除边界。
- `ProductImageFile.java`：存储读取结果。
- `ProductImageView.java`：前端可见的图片元数据。
- `ProductImageService.java`：数量、格式、主图、排序、删除和补偿逻辑。
- `ProductImageController.java`：multipart 与图片响应 HTTP 接口。

新增前端文件按职责拆分：

- `ProductImagePicker.vue`：待上传和已有图片的选择、预览、排序及主图管理。
- `ProductGalleryDialog.vue`：只读大图图库。
- 对应的两个测试文件验证组件行为。

现有 `EntityDialog.vue` 只负责协调产品保存与图片上传，`ModuleListPage.vue` 只负责缩略图入口，避免继续堆叠图片内部状态。

---

### Task 1: 数据表、本地存储接口和 Docker 持久化卷

**Files:**
- Create: `backend/app/src/main/resources/db/migration/V26__product_image_gallery.sql`
- Create: `backend/app/src/main/java/com/internalops/productimage/ProductImageStorage.java`
- Create: `backend/app/src/main/java/com/internalops/productimage/ProductImageFile.java`
- Create: `backend/app/src/main/java/com/internalops/productimage/LocalProductImageStorage.java`
- Create: `backend/app/src/test/java/com/internalops/productimage/LocalProductImageStorageTest.java`
- Modify: `backend/app/src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Modify: `.env.example`

**Interfaces:**
- Produces: `ProductImageStorage.store(long productId, String storageKey, byte[] content)`, `read(String storageKey)`, `delete(String storageKey)`.
- Produces: `ProductImageFile(byte[] content, long size)`.
- Consumes later: `ProductImageService` uses this interface without knowing the filesystem root.

- [ ] **Step 1: Write the migration and storage contract tests**

Create `LocalProductImageStorageTest` with a JUnit temporary directory:

```java
@TempDir Path root;

@Test void stores_reads_and_deletes_under_the_configured_root() throws Exception {
    var storage = new LocalProductImageStorage(root);
    storage.store(42L, "42/abc.jpg", "image".getBytes(UTF_8));
    assertThat(storage.read("42/abc.jpg").content()).isEqualTo("image".getBytes(UTF_8));
    storage.delete("42/abc.jpg");
    assertThatThrownBy(() -> storage.read("42/abc.jpg")).isInstanceOf(NoSuchFileException.class);
}

@Test void rejects_paths_that_escape_the_root() {
    var storage = new LocalProductImageStorage(root);
    assertThatThrownBy(() -> storage.store(42L, "../escape.jpg", new byte[]{1}))
        .isInstanceOf(IllegalArgumentException.class);
}
```

Create the migration with the exact table shape:

```sql
CREATE TABLE product_image (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(50) NOT NULL,
  file_size BIGINT NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order INT NOT NULL,
  uploaded_by VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES sku(id) ON DELETE CASCADE,
  CONSTRAINT uk_product_image_storage_key UNIQUE (storage_key),
  CONSTRAINT uk_product_image_order UNIQUE (product_id, sort_order),
  INDEX idx_product_image_product_primary (product_id, is_primary)
);
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
mvn -Dmaven.repo.local=.m2 -pl app -am -Dtest=LocalProductImageStorageTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the storage types do not exist.

- [ ] **Step 3: Implement the minimal local storage adapter**

Use a constructor configured by `${internal-ops.product-image-root}` and reject normalized paths outside `root`:

```java
public interface ProductImageStorage {
    void store(long productId, String storageKey, byte[] content) throws IOException;
    ProductImageFile read(String storageKey) throws IOException;
    void delete(String storageKey) throws IOException;
}

public record ProductImageFile(byte[] content, long size) {}

@Component
public final class LocalProductImageStorage implements ProductImageStorage {
    private final Path root;
    public LocalProductImageStorage(@Value("${internal-ops.product-image-root}") String root) {
        this(Path.of(root));
    }
    LocalProductImageStorage(Path root) { this.root = root.toAbsolutePath().normalize(); }
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("图片存储路径无效");
        return resolved;
    }
    public void store(long productId, String key, byte[] content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, content, StandardOpenOption.CREATE_NEW);
    }
    public ProductImageFile read(String key) throws IOException {
        byte[] bytes = Files.readAllBytes(resolve(key));
        return new ProductImageFile(bytes, bytes.length);
    }
    public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }
}
```

Add configuration:

```yaml
internal-ops:
  product-image-root: ${PRODUCT_IMAGE_ROOT:./data/product-images}
```

Add `PRODUCT_IMAGE_ROOT=/app/data/product-images`, backend volume `product_image_data:/app/data/product-images`, and top-level `product_image_data:` to `docker-compose.yml`. Document optional local value in `.env.example` without exposing a host path.

- [ ] **Step 4: Run the focused test and migration validation**

Run the focused Maven command again, then:

```powershell
docker compose config
```

Expected: storage tests pass and Compose renders a `product_image_data` volume mounted into the backend.

- [ ] **Step 5: Commit**

```powershell
git add backend/app/src/main/resources/db/migration/V26__product_image_gallery.sql backend/app/src/main/java/com/internalops/productimage backend/app/src/test/java/com/internalops/productimage backend/app/src/main/resources/application.yml docker-compose.yml .env.example
git commit -m "feat: add persistent product image storage"
```

---

### Task 2: 产品图片业务规则和受保护 API

**Files:**
- Create: `backend/app/src/main/java/com/internalops/productimage/ProductImageView.java`
- Create: `backend/app/src/main/java/com/internalops/productimage/ProductImageService.java`
- Create: `backend/app/src/main/java/com/internalops/productimage/ProductImageController.java`
- Create: `backend/app/src/main/java/com/internalops/productimage/ProductImageOrderRequest.java`
- Create: `backend/app/src/test/java/com/internalops/productimage/ProductImageApiTest.java`
- Create: `backend/app/src/test/resources/product-image-schema.sql`
- Modify: `backend/app/src/main/resources/application.yml`

**Interfaces:**
- Produces: `GET /api/products/{productId}/images`, `POST /api/products/{productId}/images`, `GET /api/product-images/{imageId}/content`, `PUT /api/products/{productId}/images/order`, `PUT /api/products/{productId}/images/{imageId}/primary`, `DELETE /api/products/{productId}/images/{imageId}`.
- Produces: `ProductImageView(id, productId, originalFilename, contentType, fileSize, primary, sortOrder, contentUrl)`.
- Consumes: `ProductImageStorage` from Task 1 and `CurrentUser.required()` for authentication/audit.

- [ ] **Step 1: Write failing API tests for upload validation and invariants**

Use `@SpringBootTest`, `@AutoConfigureMockMvc`, H2 schema setup and the project’s authenticated-session test helper. Cover these exact cases:

```java
@Test void first_valid_upload_becomes_primary_and_is_readable() throws Exception { /* JPEG magic + assertions */ }
@Test void accepts_png_and_webp_magic() throws Exception { /* valid signatures */ }
@Test void rejects_executable_renamed_to_jpg() throws Exception { /* status 400 */ }
@Test void rejects_a_file_larger_than_five_megabytes() throws Exception { /* status 400 */ }
@Test void rejects_the_eleventh_image() throws Exception { /* status 400 */ }
@Test void changing_primary_leaves_exactly_one_primary_image() throws Exception { /* query DB */ }
@Test void deleting_primary_promotes_the_lowest_sort_order() throws Exception { /* query DB */ }
@Test void all_three_authenticated_roles_can_maintain_images() throws Exception { /* ADMIN, FINANCE, USER */ }
@Test void unauthenticated_requests_are_rejected() throws Exception { /* status 401 */ }
```

Use small deterministic byte fixtures:

```java
private static byte[] jpeg() { return new byte[]{(byte)0xFF,(byte)0xD8,(byte)0xFF,0x00}; }
private static byte[] png() { return new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A}; }
private static byte[] webp() { return "RIFF0000WEBP".getBytes(US_ASCII); }
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -Dmaven.repo.local=.m2 -pl app -am -Dtest=ProductImageApiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because controller, service, request and view do not exist.

- [ ] **Step 3: Implement media detection and transactional metadata rules**

Define constants `MAX_IMAGES=10` and `MAX_BYTES=5L*1024*1024`. Detect type only from magic bytes:

```java
private String detectedType(byte[] bytes) {
    if (startsWith(bytes, new int[]{0xFF,0xD8,0xFF})) return "image/jpeg";
    if (startsWith(bytes, new int[]{0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A})) return "image/png";
    if (bytes.length >= 12 && ascii(bytes,0,4).equals("RIFF") && ascii(bytes,8,12).equals("WEBP")) return "image/webp";
    throw new IllegalArgumentException("仅支持 JPG、PNG、WebP 图片");
}
```

On upload: lock the product row with `SELECT id FROM sku WHERE id=? FOR UPDATE`, count current images, allocate `sort_order`, write file to a UUID key such as `productId/uuid.ext`, insert metadata, and compensate with `storage.delete(key)` if insertion fails. Use `CurrentUser.required().username()` for `uploaded_by`.

For primary changes, run these in one transaction:

```sql
UPDATE product_image SET is_primary=FALSE,version=version+1 WHERE product_id=?;
UPDATE product_image SET is_primary=TRUE,version=version+1 WHERE id=? AND product_id=?;
```

For ordering, require the submitted ID set to exactly match current IDs, update via temporary negative orders to avoid the unique constraint, then normalize to `0..n-1`. Make the first ordered image primary only when no explicit primary remains.

- [ ] **Step 4: Implement HTTP responses and multipart limits**

Controller outline:

```java
@PostMapping(path="/api/products/{productId}/images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
ApiResponse<List<ProductImageView>> upload(@PathVariable long productId,
                                            @RequestPart("files") List<MultipartFile> files)

@GetMapping("/api/product-images/{imageId}/content")
ResponseEntity<byte[]> content(@PathVariable long imageId)
```

For content responses set stored `Content-Type`, `Cache-Control: private, max-age=3600`, and `X-Content-Type-Options: nosniff`. Translate invalid input to 400, missing product/image to 404, version conflicts to 409, and missing files on disk to a logged 404 response. Set Spring multipart request limit to `52MB` so ten 5MB files fit while enforcing 5MB per file in the service.

- [ ] **Step 5: Run focused and full backend tests**

Run the focused command, then:

```powershell
mvn -Dmaven.repo.local=.m2 test
```

Expected: all tests pass with zero failures.

- [ ] **Step 6: Commit**

```powershell
git add backend/app/src/main/java/com/internalops/productimage backend/app/src/test/java/com/internalops/productimage backend/app/src/test/resources/product-image-schema.sql backend/app/src/main/resources/application.yml
git commit -m "feat: add product image gallery api"
```

---

### Task 3: 产品列表返回主图和图片数量

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java`
- Modify: `backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java`
- Modify: `backend/app/src/test/resources/workbench-query-schema.sql`

**Interfaces:**
- Produces on product rows: `primaryImageId: number | null`, `primaryImageUrl: string | null`, `imageCount: number`.
- Consumes later: `ModuleListPage.vue` renders these fields without loading every gallery.

- [ ] **Step 1: Add a failing product-list aggregation test**

Insert one product with three image records, exactly one primary, then assert:

```java
assertThat(item.get("imageCount")).isEqualTo(3);
assertThat(item.get("primaryImageId")).isEqualTo(primaryId);
assertThat(item.get("primaryImageUrl")).isEqualTo("/api/product-images/" + primaryId + "/content");
```

Also assert a product without images returns count `0` and null primary fields.

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn -Dmaven.repo.local=.m2 -pl app -am -Dtest=WorkbenchQueryApiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: assertions fail because image aggregation fields are absent.

- [ ] **Step 3: Extend only the product module SELECT**

Add correlated expressions to the product select:

```sql
(SELECT COUNT(*) FROM product_image pi WHERE pi.product_id=s.id) AS `imageCount`,
(SELECT pi.id FROM product_image pi WHERE pi.product_id=s.id AND pi.is_primary=TRUE LIMIT 1) AS `primaryImageId`
```

After key normalization, enrich product rows with `primaryImageUrl` from `primaryImageId`, matching the established inventory post-processing pattern. Add `imagecount` and `primaryimageid` to `CAMEL_KEYS`.

- [ ] **Step 4: Run focused and full tests, then commit**

Run the focused command and `mvn -Dmaven.repo.local=.m2 test`. Expected: all pass.

```powershell
git add backend/app/src/main/java/com/internalops/workbench/WorkbenchQueryService.java backend/app/src/test/java/com/internalops/workbench/WorkbenchQueryApiTest.java backend/app/src/test/resources/workbench-query-schema.sql
git commit -m "feat: expose product image summaries"
```

---

### Task 4: 前端图片 API 与只读图库

**Files:**
- Modify: `frontend/src/api/workbench.ts`
- Modify: `frontend/src/api/workbench.test.ts`
- Create: `frontend/src/components/ProductGalleryDialog.vue`
- Create: `frontend/src/components/ProductGalleryDialog.test.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: TypeScript `ProductImage` and functions `loadProductImages`, `uploadProductImages`, `setPrimaryProductImage`, `reorderProductImages`, `deleteProductImage`.
- Produces: `ProductGalleryDialog` props `{ productId: number; initialImageId?: number }`, event `close`.
- Consumes later: picker and list use these APIs and gallery component.

- [ ] **Step 1: Write failing API and gallery tests**

API test must assert multipart upload does not force JSON headers:

```ts
await uploadProductImages(7, [new File(['x'], 'a.jpg', { type: 'image/jpeg' })])
expect(fetch).toHaveBeenCalledWith('/api/products/7/images', expect.objectContaining({
  method: 'POST', body: expect.any(FormData)
}))
expect((fetch as Mock).mock.calls.at(-1)?.[1]?.headers).not.toHaveProperty('Content-Type')
```

Gallery test stubs two images and verifies initial selection, next/previous buttons, thumbnail selection, `alt` text and close event.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
npm test -- --run src/api/workbench.test.ts src/components/ProductGalleryDialog.test.ts
```

Expected: missing exports/component failures.

- [ ] **Step 3: Add a request helper that supports FormData**

Keep existing JSON calls unchanged but only set JSON content type when body is not `FormData`:

```ts
const formData = init?.body instanceof FormData
const headers = { ...(formData ? {} : { 'Content-Type': 'application/json' }), ...(init?.headers ?? {}) }
```

Add:

```ts
export interface ProductImage {
  id:number; productId:number; originalFilename:string; contentType:string;
  fileSize:number; primary:boolean; sortOrder:number; contentUrl:string;
}
export function loadProductImages(productId:number): Promise<ProductImage[]>
export function uploadProductImages(productId:number, files:File[]): Promise<ProductImage[]>
export function setPrimaryProductImage(productId:number, imageId:number): Promise<ProductImage[]>
export function reorderProductImages(productId:number, imageIds:number[]): Promise<ProductImage[]>
export function deleteProductImage(productId:number, imageId:number): Promise<ProductImage[]>
```

- [ ] **Step 4: Implement the accessible gallery dialog**

Render a modal with `role="dialog"`, a large `<img>`, filename, `主图` badge, previous/next controls and a horizontal thumbnail strip. Disable navigation when only one image exists. Use authenticated relative `contentUrl` values and informative alt text such as `产品图片 2/5：P90-front.jpg`.

- [ ] **Step 5: Run focused tests and frontend build, then commit**

```powershell
npm test -- --run src/api/workbench.test.ts src/components/ProductGalleryDialog.test.ts
npm run build
git add frontend/src/api/workbench.ts frontend/src/api/workbench.test.ts frontend/src/components/ProductGalleryDialog.vue frontend/src/components/ProductGalleryDialog.test.ts frontend/src/styles.css
git commit -m "feat: add product image gallery client"
```

Expected: tests and build succeed.

---

### Task 5: 产品图片选择器与新增产品保存流程

**Files:**
- Create: `frontend/src/components/ProductImagePicker.vue`
- Create: `frontend/src/components/ProductImagePicker.test.ts`
- Modify: `frontend/src/components/EntityDialog.vue`
- Modify: `frontend/src/components/EntityDialog.test.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: `ProductImagePicker` props `{ productId?: number; modelValue: File[] }`, emits `update:modelValue`, `message`, `changed`.
- Consumes: Task 4 image API.
- Changes: successful new-product save uses returned entity `id`, uploads pending files, and emits `saved` after upload attempts finish.

- [ ] **Step 1: Write failing picker validation tests**

Cover:

```ts
it('accepts jpeg png and webp files and previews them')
it('rejects a file over 5MB with a Chinese message')
it('rejects an unsupported type')
it('never allows more than ten existing plus pending images')
it('uses the first pending image as the initial primary preview')
it('revokes object URLs when removed and unmounted')
it('sets primary deletes and reorders existing images through the API')
```

Use `URL.createObjectURL` and `URL.revokeObjectURL` mocks; do not read actual disk files.

- [ ] **Step 2: Write failing EntityDialog orchestration tests**

Test the exact order and partial-failure behavior:

```ts
expect(api.createEntity).toHaveBeenCalledBefore(api.uploadProductImages)
expect(api.uploadProductImages).toHaveBeenCalledWith(81, selectedFiles)
```

When upload rejects, assert `saved` still emits, message contains `产品已保存，部分图片上传失败`, and the picker retains failed files for retry. Editing an existing product must upload directly to the existing ID.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
npm test -- --run src/components/ProductImagePicker.test.ts src/components/EntityDialog.test.ts
```

Expected: missing component and upload workflow assertions fail.

- [ ] **Step 4: Implement the picker as an isolated component**

Use an `<input type="file" accept="image/jpeg,image/png,image/webp" multiple>`, drag events, preview cards, a `主图` badge/button, move-left/move-right controls (plus drag ordering when pointer events are available), delete and retry states. Validate `file.size <= 5 * 1024 * 1024`, MIME allowlist, and total existing plus pending count before adding.

For new products, reorder pending files locally and place the chosen primary file first; the backend’s first-upload rule makes it primary. For existing products, call the explicit primary/order endpoints.

- [ ] **Step 5: Integrate without adding image fields to the JSON product payload**

In `EntityDialog.vue`, render the picker only for `module === 'product'`. Capture the entity returned by `createEntity`:

```ts
const savedEntity = props.row?.id
  ? await updateEntity(props.module, Number(props.row.id), body)
  : await createEntity(props.module, body)
const productId = Number(savedEntity.id ?? props.row?.id)
if (props.module === 'product' && pendingImages.value.length) {
  try { await uploadProductImages(productId, pendingImages.value); pendingImages.value = [] }
  catch (error) { emit('message', '产品已保存，部分图片上传失败', 'error') }
}
```

Do not close the dialog on failed image upload until the user sees retry controls; still emit the product-saved state so list data can refresh.

- [ ] **Step 6: Run focused and full frontend tests, then commit**

```powershell
npm test -- --run src/components/ProductImagePicker.test.ts src/components/EntityDialog.test.ts
npm test -- --run
npm run build
git add frontend/src/components/ProductImagePicker.vue frontend/src/components/ProductImagePicker.test.ts frontend/src/components/EntityDialog.vue frontend/src/components/EntityDialog.test.ts frontend/src/styles.css
git commit -m "feat: upload images with product editing"
```

Expected: all frontend tests and production build succeed.

---

### Task 6: 产品列表缩略图和图库入口

**Files:**
- Modify: `frontend/src/modules/module-config.ts`
- Modify: `frontend/src/modules/module-config.test.ts`
- Modify: `frontend/src/components/ModuleListPage.vue`
- Modify: `frontend/src/components/ModuleListPage.test.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/App.test.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Product module prepends virtual field `productImage` and column `图片`.
- `ModuleListPage` emits `gallery: [row: Record<string, unknown>]` when the image cell is clicked.
- `App.vue` owns the selected product gallery ID and renders `ProductGalleryDialog`.

- [ ] **Step 1: Write failing config and list rendering tests**

Assert product config starts with `columns[0] === '图片'`, `fields[0] === 'productImage'`, and empty sort key. List tests cover:

```ts
expect(wrapper.get('[data-test="product-thumbnail"] img').attributes('src')).toBe('/api/product-images/9/content')
expect(wrapper.get('[data-test="product-image-count"]').text()).toBe('5')
await wrapper.get('[data-test="product-thumbnail"]').trigger('click')
expect(wrapper.emitted('gallery')?.[0]?.[0]).toMatchObject({ id: 7 })
```

Also test `暂无图片` placeholder emits gallery so the user can inspect an empty gallery and then choose 修改 to upload.

- [ ] **Step 2: Write a failing App integration test**

Emit `gallery` from the stubbed list and assert `ProductGalleryDialog` receives the product ID, then closes cleanly.

- [ ] **Step 3: Run tests and verify RED**

```powershell
npm test -- --run src/modules/module-config.test.ts src/components/ModuleListPage.test.ts src/App.test.ts
```

Expected: missing virtual column, event and dialog assertions fail.

- [ ] **Step 4: Implement the 56×56 thumbnail cell**

Special-case only `module.key === 'product' && field === 'productImage'` before the generic text cell. Render the authenticated image URL, `loading="lazy"`, fixed dimensions, object-fit cover, count badge when `imageCount > 1`, and placeholder otherwise. Set `columnWidth('productImage')` to `84` and ensure `text()` does not turn this virtual field into a dash.

- [ ] **Step 5: Wire gallery ownership through App**

Add `@gallery="openProductGallery"` to `ModuleListPage`, store the selected product row, render `ProductGalleryDialog` at the app root, and clear state on close or module change.

- [ ] **Step 6: Run focused and full frontend checks, then commit**

```powershell
npm test -- --run src/modules/module-config.test.ts src/components/ModuleListPage.test.ts src/App.test.ts
npm test -- --run
npm run build
git add frontend/src/modules/module-config.ts frontend/src/modules/module-config.test.ts frontend/src/components/ModuleListPage.vue frontend/src/components/ModuleListPage.test.ts frontend/src/App.vue frontend/src/App.test.ts frontend/src/styles.css
git commit -m "feat: show product gallery thumbnails"
```

---

### Task 7: 删除产品清理、部署验证和运维文档

**Files:**
- Modify: `backend/app/src/main/java/com/internalops/productimage/ProductImageService.java` to add `deleteAllForProduct(long productId)` as the lifecycle hook; the current codebase has no product hard-delete endpoint, so this task does not add one.
- Modify: `backend/app/src/test/java/com/internalops/productimage/ProductImageApiTest.java`
- Modify: `README.md`

**Interfaces:**
- Produces: `ProductImageService.deleteAllForProduct(long productId)` for product lifecycle cleanup.
- Documents: volume backup/restore and destructive reset behavior.

- [ ] **Step 1: Add failing cleanup and missing-file tests**

Test that deleting all product images removes database metadata and calls storage deletion for every key. Test a missing file returns a controlled 404 with a Chinese message and leaves an error log, not a raw filesystem exception.

- [ ] **Step 2: Run focused test and verify RED**

```powershell
mvn -Dmaven.repo.local=.m2 -pl app -am -Dtest=ProductImageApiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: cleanup method or missing-file mapping assertion fails.

- [ ] **Step 3: Implement cleanup and document operations**

README must include:

```bash
# Rebuild without deleting images
docker compose up -d --build

# Back up image volume together with the database
docker run --rm -v gmt-inventory_product_image_data:/data -v "$PWD/backups:/backup" alpine \
  tar czf /backup/product-images-$(date +%F).tar.gz -C /data .

# Intentionally delete database, attachments and product images
docker compose down -v
```

State explicitly that database and image-volume backups must be taken at the same maintenance point.

- [ ] **Step 4: Run all verification commands**

```powershell
mvn -Dmaven.repo.local=.m2 test
npm test -- --run
npm run build
docker compose config
```

Expected: backend and frontend suites have zero failures, frontend production build succeeds, and Compose shows `mysql_data`, `attachment_data`, and `product_image_data`.

- [ ] **Step 5: Perform manual acceptance in the running app**

Verify in order:

1. Create a product with three selected images.
2. Confirm the first image appears as the 56×56 list thumbnail with count `3`.
3. Open the gallery and navigate all images.
4. Edit the product, change primary, reorder, delete one, and confirm the list refreshes.
5. Attempt an invalid file, a file over 5MB, and an eleventh image; verify precise Chinese messages.
6. Log in as ADMIN, FINANCE, and USER and verify all can maintain images.
7. Run `docker compose up -d --build` and confirm images remain available.

- [ ] **Step 6: Commit**

```powershell
git add backend/app/src/main/java/com/internalops/productimage/ProductImageService.java backend/app/src/test/java/com/internalops/productimage/ProductImageApiTest.java README.md
git commit -m "docs: verify product image deployment"
```

---

## 最终完成标准

- 产品列表以布局 A 显示主图缩略图、图片数量和无图占位。
- 一个产品可维护 1 张主图与最多 9 张详情图。
- 新增产品与图片选择在同一弹窗完成，并正确处理部分上传失败。
- 格式、大小、数量、主图唯一性、排序与删除规则均由后端强制执行。
- 所有已登录角色可维护，未登录请求被拒绝。
- Docker 重建保留图片，只有明确删除命名卷才清空。
- 后端全量测试、前端全量测试、生产构建和 Compose 配置检查全部通过。
