package com.internalops.workbench;

import com.internalops.auth.CurrentUser;
import com.internalops.numbering.DocumentNumberService;
import com.internalops.numbering.DocumentType;
import com.internalops.procurement.ProcurementRecommendationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProcurementWorkflowService {
    private static final BigDecimal MAX_PURCHASE_PRICE = new BigDecimal("99999999999999.9999");
    private static final BigDecimal MAX_PURCHASE_TOTAL = new BigDecimal("9999999999999999.99");

    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;
    private final DocumentNumberService documentNumbers;
    private final ProcurementRecommendationService recommendations;

    public ProcurementWorkflowService(JdbcTemplate jdbc, InventoryAllocationService allocation, DocumentNumberService documentNumbers, ProcurementRecommendationService recommendations) {
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.documentNumbers = documentNumbers;
        this.recommendations = recommendations;
    }

    @Transactional
    public Map<String, Object> generate() {
        List<Long> systemDraftSuggestionIds = clearSystemDraftCoverage();
        var missing = jdbc.queryForList("""
                SELECT i.id AS item_id, i.sku_id, o.order_no,
                       i.uncovered_quantity-COALESCE(c.covered_quantity,0) AS uncovered_quantity
                FROM sales_order_item i
                JOIN sales_order o ON o.id=i.sales_order_id
                LEFT JOIN (
                    SELECT sales_order_item_id,SUM(covered_quantity) AS covered_quantity
                    FROM shortage_coverage WHERE active=TRUE GROUP BY sales_order_item_id
                ) c ON c.sales_order_item_id=i.id
                WHERE o.status='WAITING_STOCK'
                  AND i.uncovered_quantity-COALESCE(c.covered_quantity,0)>0
                ORDER BY i.sku_id,i.id
                """);
        if (missing.isEmpty()) {
            rejectUnusedSystemDraftSuggestions(systemDraftSuggestionIds, List.of());
            return generationResult(List.of(), List.of());
        }

        Map<Long, List<Map<String, Object>>> demandRowsBySku = new LinkedHashMap<>();
        for (var row : missing) demandRowsBySku.computeIfAbsent(num(row, "sku_id"), ignored -> new ArrayList<>()).add(row);

        Map<Long, List<Map<String, Object>>> rowsBySku = new LinkedHashMap<>();
        for (var entry : demandRowsBySku.entrySet()) {
            int remainingInTransit = availableInTransitQuantity(entry.getKey());
            List<Map<String, Object>> netShortageRows = new ArrayList<>();
            for (var row : entry.getValue()) {
                int uncovered = (int) num(row, "uncovered_quantity");
                int netShortage = Math.max(0, uncovered - remainingInTransit);
                remainingInTransit = Math.max(0, remainingInTransit - uncovered);
                if (netShortage > 0) {
                    Map<String, Object> netRow = new LinkedHashMap<>(row);
                    netRow.put("uncovered_quantity", netShortage);
                    netShortageRows.add(netRow);
                }
            }
            if (!netShortageRows.isEmpty()) rowsBySku.put(entry.getKey(), netShortageRows);
        }

        Map<Long, List<ProcurementRecommendationService.Recommendation>> groups = new LinkedHashMap<>();
        List<Map<String, Object>> unconfiguredItems = new ArrayList<>();
        for (var skuEntry : rowsBySku.entrySet()) {
            int shortage = skuEntry.getValue().stream().mapToInt(row -> (int) num(row, "uncovered_quantity")).sum();
            var candidateRows = jdbc.queryForList("""
                    SELECT cfg.supplier_id,pi.id AS purchase_info_id,pi.purchase_price,pi.moq,pi.lead_time_days
                    FROM sku_supplier_config cfg
                    JOIN supplier sp ON sp.id=cfg.supplier_id AND sp.enabled=TRUE
                    JOIN (
                        SELECT ranked.* FROM (
                            SELECT info.*,ROW_NUMBER() OVER(PARTITION BY info.supplier_product_config_id ORDER BY info.updated_at DESC,info.id DESC) rn
                            FROM sku_supplier_purchase_info info WHERE info.enabled=TRUE
                        ) ranked WHERE ranked.rn=1
                    ) pi ON pi.supplier_product_config_id=cfg.id
                    WHERE cfg.sku_id=? AND cfg.enabled=TRUE
                      AND pi.purchase_price IS NOT NULL
                      AND pi.moq IS NOT NULL
                      AND pi.lead_time_days IS NOT NULL
                    """, skuEntry.getKey());
            var candidates = candidateRows.stream().map(row -> new ProcurementRecommendationService.Candidate(
                    num(row,"supplier_id"), num(row,"purchase_info_id"), (BigDecimal) val(row,"purchase_price"),
                    (int) num(row,"moq"), (int) num(row,"lead_time_days"))).toList();
            var recommendation = recommendations.recommend(skuEntry.getKey(), shortage, candidates);
            if (recommendation.isEmpty()) {
                Map<String, Object> sku = jdbc.queryForMap("SELECT id,customer_part_number,product_name FROM sku WHERE id=?", skuEntry.getKey());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skuId", num(sku, "id"));
                item.put("customerPartNumber", val(sku, "customer_part_number"));
                item.put("productName", val(sku, "product_name"));
                item.put("shortageQuantity", shortage);
                item.put("orderNumbers", skuEntry.getValue().stream()
                        .map(row -> str(row, "order_no"))
                        .filter(orderNo -> orderNo != null && !orderNo.isBlank())
                        .distinct()
                        .toList());
                unconfiguredItems.add(item);
                continue;
            }
            groups.computeIfAbsent(recommendation.get().supplierId(), ignored -> new ArrayList<>()).add(recommendation.get());
        }

        List<Long> suggestions = new ArrayList<>();
        for (var group : groups.entrySet()) {
            List<Long> existingSuggestions = jdbc.queryForList("""
                    SELECT DISTINCT ps.id
                    FROM procurement_suggestion ps
                    JOIN procurement_suggestion_item psi ON psi.suggestion_id=ps.id
                    WHERE ps.status='DRAFT' AND ps.system_managed=TRUE AND psi.supplier_id=?
                    ORDER BY ps.id LIMIT 1
                    """, Long.class, group.getKey());
            long suggestionId;
            if (existingSuggestions.isEmpty()) {
                String suggestionNo = documentNumbers.next(DocumentType.PROCUREMENT_REVIEW, LocalDate.now());
                suggestionId = insert("INSERT INTO procurement_suggestion(suggestion_no,status,system_managed,created_by) VALUES(?,'DRAFT',TRUE,1)", suggestionNo);
            } else {
                suggestionId = existingSuggestions.get(0);
                jdbc.update("DELETE FROM procurement_suggestion_item WHERE suggestion_id=?", suggestionId);
            }
            for (var recommendation : group.getValue()) {
                LocalDate eta = LocalDate.now().plusDays(recommendation.leadTimeDays());
                long suggestionItemId = insert("""
                                INSERT INTO procurement_suggestion_item(
                                    suggestion_id,sku_id,supplier_id,shortage_quantity,suggested_quantity,
                                    confirmed_quantity,purchase_price,expected_arrival_date,supplier_purchase_info_id)
                                VALUES(?,?,?,?,?,NULL,?,?,?)
                                """,
                        suggestionId, recommendation.skuId(), recommendation.supplierId(),
                        recommendation.shortageQuantity(), recommendation.suggestedQuantity(),
                        recommendation.purchasePrice(), eta, recommendation.purchaseInfoId());
                for (var item : rowsBySku.get(recommendation.skuId())) {
                    jdbc.update("""
                                    INSERT INTO shortage_coverage(
                                        sales_order_item_id,suggestion_item_id,covered_quantity,active)
                                    VALUES(?,?,?,TRUE)
                                    """, num(item,"item_id"), suggestionItemId, num(item,"uncovered_quantity"));
                }
            }
            suggestions.add(suggestionId);
        }
        rejectUnusedSystemDraftSuggestions(systemDraftSuggestionIds, suggestions);
        return generationResult(suggestions, unconfiguredItems);
    }

    private Map<String, Object> generationResult(List<Long> suggestionIds, List<Map<String, Object>> unconfiguredItems) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionIds", suggestionIds);
        result.put("count", suggestionIds.size());
        result.put("unconfiguredCount", unconfiguredItems.size());
        result.put("unconfiguredItems", unconfiguredItems);
        return result;
    }

    private int availableInTransitQuantity(long skuId) {
        Integer inTransit = jdbc.queryForObject("""
                SELECT COALESCE(SUM(balance.in_transit_quantity),0)
                FROM inventory_balance balance
                JOIN warehouse warehouse ON warehouse.id=balance.warehouse_id
                WHERE balance.sku_id=? AND warehouse.is_default=TRUE AND warehouse.enabled=TRUE
                """, Integer.class, skuId);
        Integer committedCoverage = jdbc.queryForObject("""
                SELECT COALESCE(SUM(coverage.covered_quantity),0)
                FROM shortage_coverage coverage
                JOIN procurement_suggestion_item item ON item.id=coverage.suggestion_item_id
                JOIN procurement_suggestion suggestion ON suggestion.id=item.suggestion_id
                WHERE coverage.active=TRUE AND suggestion.status='CONFIRMED' AND item.sku_id=?
                """, Integer.class, skuId);
        return Math.max(0, Objects.requireNonNullElse(inTransit, 0) - Objects.requireNonNullElse(committedCoverage, 0));
    }

    private List<Long> clearSystemDraftCoverage() {
        List<Long> suggestionIds = jdbc.queryForList("SELECT id FROM procurement_suggestion WHERE status='DRAFT' AND system_managed=TRUE FOR UPDATE", Long.class);
        if (suggestionIds.isEmpty()) return suggestionIds;
        jdbc.update("""
                DELETE FROM shortage_coverage
                WHERE suggestion_item_id IN (
                    SELECT id FROM procurement_suggestion_item
                    WHERE suggestion_id IN (SELECT id FROM procurement_suggestion WHERE status='DRAFT' AND system_managed=TRUE)
                )
                """);
        return suggestionIds;
    }

    private void rejectUnusedSystemDraftSuggestions(List<Long> systemDraftSuggestionIds, List<Long> activeSuggestionIds) {
        for (long suggestionId : systemDraftSuggestionIds) {
            if (!activeSuggestionIds.contains(suggestionId)) {
                jdbc.update("UPDATE procurement_suggestion SET status='REJECTED',review_reason='供需余量已恢复，无需采购',version=version+1 WHERE id=?", suggestionId);
            }
        }
    }

    public List<Map<String, Object>> unconfiguredShortages() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.id AS sku_id,s.customer_part_number,s.product_name,o.order_no,
                       i.uncovered_quantity-COALESCE(c.covered_quantity,0) AS shortage_quantity
                FROM sales_order_item i
                JOIN sales_order o ON o.id=i.sales_order_id
                JOIN sku s ON s.id=i.sku_id
                LEFT JOIN (
                    SELECT sales_order_item_id,SUM(covered_quantity) AS covered_quantity
                    FROM shortage_coverage WHERE active=TRUE GROUP BY sales_order_item_id
                ) c ON c.sales_order_item_id=i.id
                WHERE o.status='WAITING_STOCK'
                  AND i.uncovered_quantity-COALESCE(c.covered_quantity,0)>0
                  AND NOT EXISTS (
                    SELECT 1 FROM sku_supplier_config cfg
                    JOIN supplier sp ON sp.id=cfg.supplier_id AND sp.enabled=TRUE
                    JOIN (
                        SELECT ranked.* FROM (
                            SELECT info.*,ROW_NUMBER() OVER(PARTITION BY info.supplier_product_config_id ORDER BY info.updated_at DESC,info.id DESC) rn
                            FROM sku_supplier_purchase_info info WHERE info.enabled=TRUE
                        ) ranked WHERE ranked.rn=1
                    ) pi ON pi.supplier_product_config_id=cfg.id
                    WHERE cfg.sku_id=i.sku_id AND cfg.enabled=TRUE
                      AND pi.purchase_price IS NOT NULL
                      AND pi.moq IS NOT NULL
                      AND pi.lead_time_days IS NOT NULL
                  )
                ORDER BY s.id,o.order_no,i.id
                """);
        Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long skuId = num(row, "sku_id");
            Map<String, Object> item = grouped.computeIfAbsent(skuId, ignored -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("skuId", skuId);
                created.put("customerPartNumber", val(row, "customer_part_number"));
                created.put("productName", val(row, "product_name"));
                created.put("shortageQuantity", 0);
                created.put("orderNumbers", new LinkedHashSet<String>());
                return created;
            });
            item.put("shortageQuantity", (int) num(item, "shortageQuantity") + (int) num(row, "shortage_quantity"));
            @SuppressWarnings("unchecked")
            LinkedHashSet<String> orderNumbers = (LinkedHashSet<String>) item.get("orderNumbers");
            if (val(row, "order_no") != null) orderNumbers.add(String.valueOf(val(row, "order_no")));
        }
        grouped.values().forEach(item -> item.put("orderNumbers", List.copyOf((LinkedHashSet<?>) item.get("orderNumbers"))));
        return new ArrayList<>(grouped.values());
    }
    @Transactional
    public Map<String,Object> updateReview(long suggestionId,Map<String,Object> request) {
        int version=((Number)request.getOrDefault("version",-1)).intValue();
        List<Map<String,Object>> headers=jdbc.queryForList("SELECT status,version FROM procurement_suggestion WHERE id=? FOR UPDATE",suggestionId);
        if(headers.isEmpty()) throw new IllegalArgumentException("待确认采购不存在");
        if(!"DRAFT".equals(str(headers.get(0),"status"))) throw new IllegalStateException("当前采购建议不可修改");
        if(num(headers.get(0),"version")!=version) throw new IllegalStateException("数据已变化，请刷新后重试");
        Object rawItems=request.get("items");
        if(!(rawItems instanceof List<?> items)||items.isEmpty()) throw new IllegalArgumentException("至少填写一条采购明细");
        for(Object raw:items) {
            if(!(raw instanceof Map<?,?> input)) throw new IllegalArgumentException("采购明细格式不正确");
            long itemId=((Number)input.get("id")).longValue();
            int quantity=((Number)input.get("quantity")).intValue();
            List<Map<String,Object>> lines=jdbc.queryForList("""
                    SELECT psi.id,pi.moq FROM procurement_suggestion_item psi
                    JOIN sku_supplier_purchase_info pi ON pi.id=psi.supplier_purchase_info_id
                    WHERE psi.id=? AND psi.suggestion_id=?
                    """,itemId,suggestionId);
            if(lines.isEmpty()) throw new IllegalArgumentException("采购明细不存在");
            int moq=(int)num(lines.get(0),"moq");
            if(quantity<moq) throw new IllegalArgumentException("采购数量不能低于最小起购量 "+moq);
            LocalDate eta=input.get("expectedArrivalDate")==null?null:LocalDate.parse(String.valueOf(input.get("expectedArrivalDate")));
            jdbc.update("UPDATE procurement_suggestion_item SET suggested_quantity=?,expected_arrival_date=? WHERE id=?",quantity,eta,itemId);
        }
        int changed=jdbc.update("UPDATE procurement_suggestion SET manually_edited=TRUE,version=version+1 WHERE id=? AND version=?",suggestionId,version);
        if(changed==0) throw new IllegalStateException("数据已变化，请刷新后重试");
        return Map.of("id",suggestionId,"status","DRAFT","version",version+1);
    }
    public Map<String,Object> review(long suggestionId) {
        List<Map<String,Object>> headers = jdbc.queryForList("""
                SELECT ps.id,ps.suggestion_no,ps.status,ps.version,ps.review_reason,
                       psi.supplier_id,sp.supplier_name
                FROM procurement_suggestion ps
                JOIN procurement_suggestion_item psi ON psi.suggestion_id=ps.id
                JOIN supplier sp ON sp.id=psi.supplier_id
                WHERE ps.id=?
                ORDER BY psi.id LIMIT 1
                """, suggestionId);
        if (headers.isEmpty()) throw new IllegalArgumentException("待确认采购不存在");
        var header=headers.get(0);
        List<Map<String,Object>> items=jdbc.query("""
                SELECT psi.id,psi.sku_id,s.product_code,s.customer_part_number,s.model,s.product_name,psi.shortage_quantity,
                       psi.suggested_quantity,psi.purchase_price,psi.expected_arrival_date,
                       psi.supplier_purchase_info_id,pi.moq
                FROM procurement_suggestion_item psi
                JOIN sku s ON s.id=psi.sku_id
                LEFT JOIN sku_supplier_purchase_info pi ON pi.id=psi.supplier_purchase_info_id
                WHERE psi.suggestion_id=? ORDER BY psi.id
                """,(rs,n)->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",rs.getLong("id")); item.put("skuId",rs.getLong("sku_id"));
            item.put("productCode",rs.getString("product_code")); item.put("customerPartNumber",rs.getString("customer_part_number")); item.put("model",rs.getString("model")); item.put("productName",rs.getString("product_name"));
            item.put("shortageQuantity",rs.getInt("shortage_quantity"));
            item.put("minimumOrderQuantity",rs.getInt("moq"));
            item.put("suggestedQuantity",rs.getInt("suggested_quantity"));
            item.put("purchasePrice",rs.getBigDecimal("purchase_price"));
            item.put("estimatedAmount",rs.getBigDecimal("purchase_price").multiply(BigDecimal.valueOf(rs.getInt("suggested_quantity"))).setScale(2,RoundingMode.HALF_UP));
            item.put("expectedArrivalDate",rs.getObject("expected_arrival_date",LocalDate.class));
            item.put("supplierPurchaseInfoId",rs.getLong("supplier_purchase_info_id"));
            return item;
        },suggestionId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",num(header,"id")); result.put("suggestionNo",val(header,"suggestion_no"));
        result.put("status",val(header,"status")); result.put("version",num(header,"version"));
        result.put("supplierId",num(header,"supplier_id")); result.put("supplierName",val(header,"supplier_name"));
        result.put("reviewReason",val(header,"review_reason")); result.put("items",items);
        return result;
    }

    @Transactional
    public Map<String,Object> reject(long suggestionId,int version,String reason) {
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("驳回原因不能为空");
        int changed=jdbc.update("""
                UPDATE procurement_suggestion SET status='REJECTED',review_reason=?,version=version+1
                WHERE id=? AND status='DRAFT' AND version=?
                """,reason.trim(),suggestionId,version);
        if(changed==0) throw new IllegalStateException("数据已变化，请刷新后重试");
        jdbc.update("UPDATE shortage_coverage SET active=FALSE WHERE suggestion_item_id IN (SELECT id FROM procurement_suggestion_item WHERE suggestion_id=?)",suggestionId);
        return Map.of("id",suggestionId,"status","REJECTED","version",version+1);
    }
    @Transactional
    public Map<String, Object> confirm(long suggestionId) {
        var suggestion = jdbc.queryForMap(
                "SELECT suggestion_no,status FROM procurement_suggestion WHERE id=? FOR UPDATE", suggestionId);
        if (!"DRAFT".equals(str(suggestion, "status"))) {
            throw new IllegalStateException("当前采购建议不能重复确认");
        }
        var lines = jdbc.queryForList("""
                SELECT sku_id,supplier_id,suggested_quantity,purchase_price,expected_arrival_date,supplier_purchase_info_id
                FROM procurement_suggestion_item WHERE suggestion_id=? ORDER BY id
                """, suggestionId);
        if (lines.isEmpty()) {
            throw new IllegalStateException("采购建议没有可确认的明细");
        }
        long supplierId = num(lines.get(0), "supplier_id");
        for (var line : lines) {
            if (num(line, "supplier_id") != supplierId) {
                throw new IllegalStateException("同一采购建议只能绑定一个供应商");
            }
            if (num(line, "suggested_quantity") <= 0) {
                throw new IllegalStateException("确认采购数量必须大于零");
            }
        }

        BigDecimal total = lines.stream()
                .map(line -> ((BigDecimal) val(line, "purchase_price"))
                        .multiply(BigDecimal.valueOf(num(line, "suggested_quantity"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        LocalDate eta = lines.stream()
                .map(line -> localDate(val(line, "expected_arrival_date")))
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        String deliveryAddress = linkedOrderDeliveryAddresses(suggestionId);
        String purchaseNo = documentNumbers.next(DocumentType.PURCHASE_ORDER, LocalDate.now());
        long purchaseId = insert("""
                        INSERT INTO purchase_order(
                            purchase_no,suggestion_id,supplier_id,status,total_amount,expected_arrival_date,delivery_address,created_by)
                        VALUES(?,?,?,'PENDING_SUPPLIER_PAYMENT',?,?,?,1)
                        """,
                purchaseNo, suggestionId, supplierId, total, eta, deliveryAddress);

        int lineNo = 1;
        for (var line : lines) {
            long skuId = num(line, "sku_id");
            int quantity = (int) num(line, "suggested_quantity");
            BigDecimal price = (BigDecimal) val(line, "purchase_price");
            jdbc.update("""
                            INSERT INTO purchase_order_item(
                                purchase_order_id,line_no,sku_id,quantity,received_quantity,purchase_price,supplier_purchase_info_id)
                            VALUES(?,?,?,?,0,?,?)
                            """,
                    purchaseId, lineNo++, skuId, quantity, price, val(line, "supplier_purchase_info_id"));
            increaseTransit(skuId, quantity, purchaseNo);
        }
        jdbc.update("UPDATE procurement_suggestion_item SET confirmed_quantity=suggested_quantity WHERE suggestion_id=?",
                suggestionId);
        jdbc.update("""
                        UPDATE procurement_suggestion
                        SET status='CONFIRMED',confirmed_by=1,confirmed_at=?,version=version+1
                        WHERE id=?
                        """,
                LocalDateTime.now(), suggestionId);
        return Map.of(
                "suggestionId", suggestionId,
                "purchaseId", purchaseId,
                "purchaseNo", purchaseNo,
                "status", "PENDING_SUPPLIER_PAYMENT");
    }

    @Transactional
    public Map<String, Object> manual(ManualPurchaseRequest request) {
        if (request.quantity() == 0) throw new IllegalArgumentException("采购数量不能为零");
        List<Map<String, Object>> matches = jdbc.queryForList("""
                SELECT pi.id,pi.purchase_price,pi.moq,cfg.sku_id,cfg.supplier_id
                FROM sku_supplier_purchase_info pi
                JOIN sku_supplier_config cfg ON cfg.id=pi.supplier_product_config_id
                JOIN supplier sp ON sp.id=cfg.supplier_id
                JOIN sku s ON s.id=cfg.sku_id
                WHERE pi.id=? AND pi.enabled=TRUE AND cfg.enabled=TRUE
                  AND sp.enabled=TRUE AND s.enabled=TRUE
                  AND cfg.supplier_id=? AND cfg.sku_id=?
                  AND pi.purchase_price IS NOT NULL
                  AND pi.moq IS NOT NULL
                  AND pi.lead_time_days IS NOT NULL
                """, request.supplierPurchaseInfoId(), request.supplierId(), request.skuId());
        if (matches.isEmpty()) throw new IllegalArgumentException("所选采购信息不属于该供应商和产品，或已停用");
        Map<String, Object> quote = matches.get(0);
        int moq = (int) num(quote, "moq");
        if (request.quantity() > 0 && request.quantity() < moq) throw new IllegalArgumentException("采购数量不能低于最小起订量 " + moq);
        BigDecimal purchasePrice = (BigDecimal) val(quote, "purchase_price");
        BigDecimal total = purchasePrice.multiply(BigDecimal.valueOf(request.quantity())).setScale(2, RoundingMode.HALF_UP);
        if (total.abs().compareTo(MAX_PURCHASE_TOTAL) > 0)
            throw new IllegalArgumentException("采购总额超出数据库金额范围");
        String purchaseNo = documentNumbers.next(DocumentType.PURCHASE_ORDER, LocalDate.now());
        long purchaseId = insert("""
                INSERT INTO purchase_order(
                    purchase_no,suggestion_id,manual_entry,supplier_id,status,total_amount,
                    expected_arrival_date,delivery_address,purchase_remark,created_by)
                VALUES(?,NULL,TRUE,?,'PENDING_SUPPLIER_PAYMENT',?,?,?,?,1)
                """, purchaseNo, request.supplierId(), total, request.expectedArrivalDate(), request.deliveryAddress(), request.remark());
        jdbc.update("""
                INSERT INTO purchase_order_item(
                    purchase_order_id,line_no,sku_id,quantity,received_quantity,purchase_price,supplier_purchase_info_id)
                VALUES(?,1,?,?,?,?,?)
                """, purchaseId, request.skuId(), request.quantity(), request.quantity() < 0 ? request.quantity() : 0, purchasePrice, request.supplierPurchaseInfoId());
        if (request.quantity() > 0) increaseTransit(request.skuId(), request.quantity(), purchaseNo);
        return Map.of("purchaseId", purchaseId, "purchaseNo", purchaseNo, "status", "PENDING_SUPPLIER_PAYMENT");
    }

    @Transactional
    public Map<String, Object> updateManual(long purchaseId, ManualPurchaseRequest request) {
        if (request.quantity() == 0) throw new IllegalArgumentException("采购数量不能为零；退货请填写负数");
        Map<String, Object> purchase = jdbc.queryForMap("""
                SELECT purchase_no,manual_entry,status FROM purchase_order WHERE id=? FOR UPDATE
                """, purchaseId);
        if (!Boolean.TRUE.equals(purchase.get("manual_entry"))) throw new IllegalStateException("系统生成的采购单不能手工修改");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payment WHERE purchase_order_id=?", Integer.class, purchaseId) > 0)
            throw new IllegalStateException("已登记付款的采购单不能修改");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipt WHERE purchase_order_id=?", Integer.class, purchaseId) > 0)
            throw new IllegalStateException("已登记收货的采购单不能修改");
        List<Map<String, Object>> oldItems = jdbc.queryForList("""
                SELECT id,sku_id,quantity FROM purchase_order_item WHERE purchase_order_id=? ORDER BY line_no FOR UPDATE
                """, purchaseId);
        if (oldItems.size() != 1) throw new IllegalStateException("仅支持修改单产品的手工采购单");

        List<Map<String, Object>> matches = jdbc.queryForList("""
                SELECT pi.purchase_price,pi.moq FROM sku_supplier_purchase_info pi
                JOIN sku_supplier_config cfg ON cfg.id=pi.supplier_product_config_id
                JOIN supplier sp ON sp.id=cfg.supplier_id
                JOIN sku s ON s.id=cfg.sku_id
                WHERE pi.id=? AND pi.enabled=TRUE AND cfg.enabled=TRUE AND sp.enabled=TRUE AND s.enabled=TRUE
                  AND cfg.supplier_id=? AND cfg.sku_id=?
                  AND pi.purchase_price IS NOT NULL AND pi.moq IS NOT NULL AND pi.lead_time_days IS NOT NULL
                """, request.supplierPurchaseInfoId(), request.supplierId(), request.skuId());
        if (matches.isEmpty()) throw new IllegalArgumentException("所选采购信息不属于该供应商和产品，或已停用");
        Map<String, Object> quote = matches.get(0);
        int moq = (int) num(quote, "moq");
        if (request.quantity() > 0 && request.quantity() < moq) throw new IllegalArgumentException("采购数量不能低于最小起订量 " + moq);
        BigDecimal price = (BigDecimal) val(quote, "purchase_price");
        BigDecimal total = price.multiply(BigDecimal.valueOf(request.quantity())).setScale(2, RoundingMode.HALF_UP);
        if (total.abs().compareTo(MAX_PURCHASE_TOTAL) > 0) throw new IllegalArgumentException("采购总额超出数据库金额范围");

        Map<String, Object> oldItem = oldItems.get(0);
        int oldQuantity = (int) num(oldItem, "quantity");
        long oldSkuId = num(oldItem, "sku_id");
        String purchaseNo = str(purchase, "purchase_no");
        if (oldQuantity > 0) increaseTransit(oldSkuId, -oldQuantity, purchaseNo);
        jdbc.update("""
                UPDATE purchase_order
                SET supplier_id=?,total_amount=?,expected_arrival_date=?,delivery_address=?,purchase_remark=?,version=version+1
                WHERE id=?
                """, request.supplierId(), total, request.expectedArrivalDate(), request.deliveryAddress(), request.remark(), purchaseId);
        jdbc.update("""
                UPDATE purchase_order_item
                SET sku_id=?,quantity=?,received_quantity=?,purchase_price=?,supplier_purchase_info_id=?
                WHERE id=?
                """, request.skuId(), request.quantity(), request.quantity() < 0 ? request.quantity() : 0,
                price, request.supplierPurchaseInfoId(), num(oldItem, "id"));
        if (request.quantity() > 0) increaseTransit(request.skuId(), request.quantity(), purchaseNo);
        return Map.of("purchaseId", purchaseId, "purchaseNo", purchaseNo, "status", val(purchase, "status"));
    }

    @Transactional
    public Map<String, Object> updatePurchaseHeader(long purchaseId, PurchaseHeaderUpdateRequest request) {
        Map<String, Object> purchase = jdbc.queryForMap("SELECT purchase_no,status FROM purchase_order WHERE id=? FOR UPDATE", purchaseId);
        jdbc.update("""
                UPDATE purchase_order
                SET expected_arrival_date=?,delivery_address=?,purchase_remark=?,version=version+1
                WHERE id=?
                """, request.expectedArrivalDate(), request.deliveryAddress(), request.remark(), purchaseId);
        return Map.of("purchaseId", purchaseId, "purchaseNo", str(purchase, "purchase_no"), "status", val(purchase, "status"));
    }

    @Transactional
    public Map<String, Object> payment(long id, FinanceActionRequest request) {
        var purchase = jdbc.queryForMap("SELECT status,total_amount FROM purchase_order WHERE id=? FOR UPDATE", id);
        BigDecimal total = (BigDecimal) val(purchase, "total_amount");
        BigDecimal paid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)),0) FROM supplier_payment WHERE purchase_order_id=? AND COALESCE(review_status, 'APPROVED')='APPROVED'",
                BigDecimal.class, id);
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("付款金额必须大于 0；发票请单独维护");
        }
        if (request.invoiceNo() != null && !request.invoiceNo().isBlank() || request.invoiceDate() != null) {
            throw new IllegalArgumentException("发票请单独维护，不能随付款登记");
        }
        BigDecimal outstanding = total.subtract(paid);
        if (amount.compareTo(outstanding) > 0) throw new IllegalArgumentException("本次付款金额不能超过未付金额");
        jdbc.update("""
                        INSERT INTO supplier_payment(
                            purchase_order_id,amount,payment_method,payment_remark,paid_at,confirmed_by,review_status)
                        VALUES(?,?,?,?,?,NULL,'PENDING')
                        """,
                id, amount, request.paymentMethod() == null || request.paymentMethod().isBlank() ? "银行转账" : request.paymentMethod().trim(),
                optionalText(request.paymentRemark()), LocalDateTime.now());
        return Map.of("id", id, "paidAmount", paid, "outstandingAmount", total.subtract(paid),
                "paymentStatus", paid.compareTo(total) >= 0 ? "PAID" : "PARTIALLY_PAID", "reviewStatus", "PENDING");
    }

    @Transactional
    public Map<String, Object> paymentByPurchaseNo(String purchaseNo, FinanceActionRequest request) {
        if (purchaseNo == null || purchaseNo.isBlank()) {
            throw new IllegalArgumentException("采购单号不能为空");
        }
        List<Long> purchaseIds = jdbc.queryForList(
                "SELECT id FROM purchase_order WHERE purchase_no=?", Long.class, purchaseNo);
        if (purchaseIds.isEmpty()) {
            throw new IllegalArgumentException("采购单不存在");
        }
        return payment(purchaseIds.get(0), request);
    }

    @Transactional
    public Map<String, Object> receive(long id, PurchaseReceiptRequest request) {
        var purchase = jdbc.queryForMap("SELECT purchase_no,status FROM purchase_order WHERE id=? FOR UPDATE", id);
        if (request == null || request.items() == null || request.items().isEmpty())
            throw new IllegalArgumentException("本次至少填写一项实收数量");
        String purchaseNo = str(purchase, "purchase_no");
        var lines = jdbc.queryForList("""
                SELECT id,sku_id,quantity,received_quantity,purchase_price
                FROM purchase_order_item WHERE purchase_order_id=? FOR UPDATE
                """, id);
        Map<Long, Map<String, Object>> linesById = new LinkedHashMap<>();
        for (var line : lines) linesById.put(num(line, "id"), line);
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        boolean canEditProductPrice = CurrentUser.required().role().canEditProductPrice();
        boolean hasPositive = false;
        for (PurchaseReceiptRequest.Item item : request.items()) {
            if (!linesById.containsKey(item.purchaseOrderItemId())) throw new IllegalArgumentException("采购明细不存在或不属于当前采购单");
            if (quantities.containsKey(item.purchaseOrderItemId())) throw new IllegalArgumentException("采购明细不能重复提交");
            int quantity = item.receivedQuantity() == null ? 0 : item.receivedQuantity();
            if (quantity < 0) throw new IllegalArgumentException("本次实收数量不能小于 0");
            Map<String, Object> line = linesById.get(item.purchaseOrderItemId());
            if (quantity > num(line, "quantity") - num(line, "received_quantity"))
                throw new IllegalArgumentException("本次实收数量不能超过剩余数量");
            quantities.put(item.purchaseOrderItemId(), quantity);
            hasPositive |= quantity > 0;
        }
        if (!hasPositive) throw new IllegalArgumentException("本次至少填写一项实收数量");
        long receipt = insert("""
                        INSERT INTO goods_receipt(
                            receipt_no,purchase_order_id,received_at,received_by,status)
                        VALUES(?,?,?,1,'COMPLETED')
                        """,
                "GR" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), id, LocalDateTime.now());
        for (var entry : quantities.entrySet()) {
            var line = linesById.get(entry.getKey());
            int quantity = entry.getValue();
            if (quantity <= 0) {
                continue;
            }
            long skuId = num(line, "sku_id");
            BigDecimal price = (BigDecimal) val(line, "purchase_price");
            receiveStock(skuId, quantity, purchaseNo);
            jdbc.update("UPDATE purchase_order_item SET received_quantity=received_quantity+? WHERE id=?", quantity, num(line, "id"));
            jdbc.update("""
                            INSERT INTO goods_receipt_item(
                                goods_receipt_id,purchase_order_item_id,accepted_quantity,rejected_quantity)
                            VALUES(?,?,?,0)
                            """,
                    receipt, num(line, "id"), quantity);
            if (canEditProductPrice) {
                BigDecimal oldCost = jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=?", BigDecimal.class, skuId);
                jdbc.update("UPDATE sku SET current_cost=?,version=version+1 WHERE id=?", price, skuId);
                jdbc.update("""
                                INSERT INTO sku_cost_history(
                                    sku_id,old_cost,new_cost,source_type,source_no,effective_at,operated_by)
                                VALUES(?,?,?,'PURCHASE_RECEIPT',?,?,1)
                                """,
                        skuId, oldCost, price, purchaseNo, LocalDateTime.now());
            }
        }
        updatePurchaseProgressStatus(id);
        allocation.reallocateWaiting();
        Map<String, Object> totals = jdbc.queryForMap("SELECT COALESCE(SUM(quantity),0) ordered_quantity,COALESCE(SUM(received_quantity),0) received_quantity FROM purchase_order_item WHERE purchase_order_id=?", id);
        long ordered = num(totals, "ordered_quantity");
        long received = num(totals, "received_quantity");
        return Map.of("id", id, "receivedQuantity", received, "remainingQuantity", ordered - received,
                "receiptStatus", ordered == received ? "RECEIVED" : "PARTIALLY_RECEIVED");
    }

    public Map<String, Object> purchase(long id) {
        List<Map<String, Object>> headers = jdbc.queryForList("""
                SELECT po.id,po.purchase_no,po.supplier_id,po.manual_entry,po.total_amount,po.status,DATE(po.created_at) AS order_date,
                       po.expected_arrival_date,po.delivery_address,po.purchase_remark,sp.supplier_name
                FROM purchase_order po JOIN supplier sp ON sp.id=po.supplier_id WHERE po.id=?
                """, id);
        if (headers.isEmpty()) throw new IllegalArgumentException("采购单不存在");
        Map<String, Object> source = headers.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(source, "id"));
        result.put("purchaseNo", val(source, "purchase_no"));
        result.put("supplierId", num(source, "supplier_id"));
        result.put("manualEntry", source.get("manual_entry"));
        result.put("supplierName", val(source, "supplier_name"));
        result.put("totalAmount", val(source, "total_amount"));
        result.put("status", val(source, "status"));
        result.put("orderDate", source.get("order_date"));
        result.put("expectedArrivalDate", source.get("expected_arrival_date"));
        result.put("deliveryAddress", val(source, "delivery_address"));
        result.put("remark", val(source, "purchase_remark"));
        List<Map<String, Object>> itemRows = jdbc.queryForList("""
                SELECT poi.id,poi.sku_id,poi.supplier_purchase_info_id,s.product_code,s.customer_part_number,s.model,s.product_name,s.product_type,s.product_configuration,s.color,s.lock_body,s.product_version,s.configuration,s.unit,poi.quantity,poi.received_quantity,poi.purchase_price,
                       poi.quantity-poi.received_quantity AS remaining_quantity
                FROM purchase_order_item poi JOIN sku s ON s.id=poi.sku_id
                WHERE poi.purchase_order_id=? ORDER BY poi.line_no
                """, id);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : itemRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", num(row, "id"));
            item.put("skuId", num(row, "sku_id"));
            // Historical purchase items can predate this optional quotation link.
            item.put("supplierPurchaseInfoId", nullableNum(row, "supplier_purchase_info_id"));
            item.put("productCode", val(row, "product_code"));
            item.put("customerPartNumber", val(row, "customer_part_number"));
            item.put("model", val(row, "model"));
            item.put("productName", val(row, "product_name"));
            item.put("productType", val(row, "product_type"));
            item.put("productConfiguration", val(row, "product_configuration"));
            item.put("color", val(row, "color"));
            item.put("lockBody", val(row, "lock_body"));
            item.put("productVersion", val(row, "product_version"));
            item.put("configuration", val(row, "configuration"));
            item.put("unit", val(row, "unit"));
            item.put("quantity", num(row, "quantity"));
            item.put("receivedQuantity", num(row, "received_quantity"));
            item.put("remainingQuantity", num(row, "remaining_quantity"));
            item.put("purchasePrice", row.get("purchase_price"));
            items.add(item);
        }
        result.put("items", items);
        return result;
    }

    void updatePurchaseProgressStatus(long id) {
        BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM purchase_order WHERE id=?", BigDecimal.class, id);
        BigDecimal paid = jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)),0) FROM supplier_payment WHERE purchase_order_id=? AND COALESCE(review_status, 'APPROVED')='APPROVED'", BigDecimal.class, id);
        Map<String, Object> quantities = jdbc.queryForMap("SELECT COALESCE(SUM(quantity),0) ordered_quantity,COALESCE(SUM(received_quantity),0) received_quantity FROM purchase_order_item WHERE purchase_order_id=?", id);
        boolean paidInFull = paid.compareTo(total) >= 0;
        boolean receivedInFull = num(quantities, "ordered_quantity") == num(quantities, "received_quantity");
        String status = paidInFull && receivedInFull ? "COMPLETED" : "EXECUTING";
        jdbc.update("UPDATE purchase_order SET status=?,version=version+1 WHERE id=?", status, id);
    }

    private String linkedOrderDeliveryAddresses(long suggestionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT o.order_no,o.delivery_address
                FROM shortage_coverage coverage
                JOIN procurement_suggestion_item item ON item.id=coverage.suggestion_item_id
                JOIN sales_order_item order_item ON order_item.id=coverage.sales_order_item_id
                JOIN sales_order o ON o.id=order_item.sales_order_id
                WHERE item.suggestion_id=? AND coverage.active=TRUE
                  AND o.delivery_address IS NOT NULL AND TRIM(o.delivery_address)<>''
                ORDER BY o.order_no
                """, suggestionId);
        return rows.stream()
                .map(row -> str(row, "order_no") + "：" + str(row, "delivery_address"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void increaseTransit(long skuId, int quantity, String purchaseNo) {
        long warehouseId = warehouse();
        jdbc.update("""
                        INSERT INTO inventory_balance(
                            warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity)
                        VALUES(?,?,0,0,0) ON DUPLICATE KEY UPDATE sku_id=VALUES(sku_id)
                        """,
                warehouseId, skuId);
        var balance = jdbc.queryForMap("""
                SELECT id,actual_quantity,locked_quantity,in_transit_quantity
                FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE
                """, warehouseId, skuId);
        int transitBefore = (int) num(balance, "in_transit_quantity");
        if (transitBefore + quantity < 0) throw new IllegalStateException("在途数量不足，无法修改采购单");
        jdbc.update("""
                        UPDATE inventory_balance
                        SET in_transit_quantity=in_transit_quantity+?,version=version+1 WHERE id=?
                        """,
                quantity, num(balance, "id"));
        allocation.tx(warehouseId, skuId, "PURCHASE_TRANSIT", "PURCHASE_ORDER", purchaseNo,
                0, 0, quantity,
                (int) num(balance, "actual_quantity"), (int) num(balance, "actual_quantity"),
                (int) num(balance, "locked_quantity"), (int) num(balance, "locked_quantity"),
                transitBefore, transitBefore + quantity);
    }

    private void receiveStock(long skuId, int quantity, String purchaseNo) {
        long warehouseId = warehouse();
        var balance = jdbc.queryForMap("""
                SELECT id,actual_quantity,locked_quantity,in_transit_quantity
                FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE
                """, warehouseId, skuId);
        int actual = (int) num(balance, "actual_quantity");
        int locked = (int) num(balance, "locked_quantity");
        int transit = (int) num(balance, "in_transit_quantity");
        jdbc.update("""
                        UPDATE inventory_balance
                        SET actual_quantity=actual_quantity+?,
                            in_transit_quantity=GREATEST(0,in_transit_quantity-?),version=version+1
                        WHERE id=?
                        """,
                quantity, quantity, num(balance, "id"));
        allocation.tx(warehouseId, skuId, "PURCHASE_RECEIPT", "PURCHASE_ORDER", purchaseNo,
                quantity, 0, -Math.min(transit, quantity),
                actual, actual + quantity, locked, locked, transit, Math.max(0, transit - quantity));
    }

    private long warehouse() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1
                """, Long.class));
    }

    private BigDecimal manualPurchasePrice(BigDecimal purchasePrice) {
        if (purchasePrice == null || purchasePrice.signum() <= 0) {
            throw new IllegalArgumentException("采购单价必须大于零");
        }
        try {
            BigDecimal databaseScalePrice = purchasePrice.setScale(4, RoundingMode.UNNECESSARY);
            if (databaseScalePrice.compareTo(MAX_PURCHASE_PRICE) > 0) {
                throw new IllegalArgumentException("采购单价超出数据库金额范围");
            }
            return databaseScalePrice;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("采购单价最多只能保留4位小数", exception);
        }
    }

    private void requireExists(String table, long id, String message) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id);
        if (count == null || count == 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private long insert(String sql, Object... parameters) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private Object val(Map<String, Object> values, String key) {
        for (var entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private long num(Map<String, Object> values, String key) {
        return ((Number) Objects.requireNonNull(val(values, key))).longValue();
    }

    private Long nullableNum(Map<String, Object> values, String key) {
        Object value = val(values, key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String str(Map<String, Object> values, String key) {
        return String.valueOf(val(values, key));
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
