package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.*;

@Service
public class CustomerManagementService {
    private final JdbcTemplate jdbc;
    public CustomerManagementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Map<String,Object> create(CustomerCommandRequest request) {
        validate(request);
        String code = emptyToNull(request.customerCode());
        if (code == null) code = "C" + UUID.randomUUID().toString().replace("-", "").substring(0,10).toUpperCase();
        long id = insert("INSERT INTO customer(customer_code,customer_name,contact_name,phone,address,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,invoice_title,taxpayer_id,invoice_address,invoice_phone,bank_name,bank_account,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)",
                code, trim(request.customerName()), emptyToNull(request.businessContactName()), emptyToNull(request.businessContactPhone()), emptyToNull(request.address()),
                emptyToNull(request.businessContactName()), emptyToNull(request.businessContactPhone()), emptyToNull(request.orderContactName()), emptyToNull(request.orderContactPhone()),
                emptyToNull(request.financeContactName()), emptyToNull(request.financeContactPhone()), emptyToNull(request.invoiceTitle()), emptyToNull(request.taxpayerId()),
                emptyToNull(request.invoiceAddress()), emptyToNull(request.invoicePhone()), emptyToNull(request.bankName()), emptyToNull(request.bankAccount()));
        replaceContracts(id, request.contracts());
        return detail(id);
    }

    @Transactional
    public Map<String,Object> update(long id, CustomerCommandRequest request) {
        validate(request);
        if (request.version() == null) throw new IllegalArgumentException("缺少数据版本，请重新打开后再试");
        int changed = jdbc.update("UPDATE customer SET customer_name=?,contact_name=?,phone=?,address=?,business_contact_name=?,business_contact_phone=?,order_contact_name=?,order_contact_phone=?,finance_contact_name=?,finance_contact_phone=?,invoice_title=?,taxpayer_id=?,invoice_address=?,invoice_phone=?,bank_name=?,bank_account=?,version=version+1 WHERE id=? AND version=?",
                trim(request.customerName()), emptyToNull(request.businessContactName()), emptyToNull(request.businessContactPhone()), emptyToNull(request.address()),
                emptyToNull(request.businessContactName()), emptyToNull(request.businessContactPhone()), emptyToNull(request.orderContactName()), emptyToNull(request.orderContactPhone()),
                emptyToNull(request.financeContactName()), emptyToNull(request.financeContactPhone()), emptyToNull(request.invoiceTitle()), emptyToNull(request.taxpayerId()),
                emptyToNull(request.invoiceAddress()), emptyToNull(request.invoicePhone()), emptyToNull(request.bankName()), emptyToNull(request.bankAccount()), id, request.version());
        if (changed == 0) throw new IllegalStateException("客户资料已被其他操作修改，请重新打开后再试");
        replaceContracts(id, request.contracts());
        return detail(id);
    }

    public Map<String,Object> detail(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,customer_code AS customerCode,customer_name AS customerName,address,business_contact_name AS businessContactName,business_contact_phone AS businessContactPhone,order_contact_name AS orderContactName,order_contact_phone AS orderContactPhone,finance_contact_name AS financeContactName,finance_contact_phone AS financeContactPhone,invoice_title AS invoiceTitle,taxpayer_id AS taxpayerId,invoice_address AS invoiceAddress,invoice_phone AS invoicePhone,bank_name AS bankName,bank_account AS bankAccount,version FROM customer WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("客户不存在");
        Map<String,Object> result = camel(rows.get(0));
        List<Map<String,Object>> contracts = jdbc.queryForList("SELECT id,contract_no AS contractNo,start_date AS startDate,end_date AS endDate,remark FROM customer_contract WHERE customer_id=? AND enabled=TRUE ORDER BY start_date DESC,id DESC", id).stream().map(this::camel).map(LinkedHashMap::new).map(row -> {
            row.put("prices", jdbc.queryForList("SELECT p.sku_id AS skuId,p.sale_price AS salePrice,s.sku_code AS skuCode,s.product_name AS productName FROM customer_contract_price p JOIN sku s ON s.id=p.sku_id WHERE p.contract_id=? ORDER BY s.sku_code,s.id", row.get("id")).stream().map(this::camel).toList());
            return (Map<String,Object>) row;
        }).toList();
        result.put("contracts", contracts);
        return result;
    }

    private void replaceContracts(long customerId, List<CustomerContractRequest> contracts) {
        jdbc.update("DELETE FROM customer_contract_price WHERE contract_id IN (SELECT id FROM customer_contract WHERE customer_id=?)", customerId);
        jdbc.update("DELETE FROM customer_contract WHERE customer_id=?", customerId);
        for (CustomerContractRequest contract : contracts == null ? List.<CustomerContractRequest>of() : contracts) {
            long contractId = insert("INSERT INTO customer_contract(customer_id,contract_no,start_date,end_date,remark,enabled) VALUES(?,?,?,?,?,TRUE)", customerId, trim(contract.contractNo()), contract.startDate(), contract.endDate(), emptyToNull(contract.remark()));
            for (CustomerContractPriceRequest price : contract.prices() == null ? List.<CustomerContractPriceRequest>of() : contract.prices()) {
                jdbc.update("INSERT INTO customer_contract_price(contract_id,sku_id,sale_price) VALUES(?,?,?)", contractId, price.skuId(), price.salePrice().setScale(4, RoundingMode.UNNECESSARY));
            }
        }
    }

    private void validate(CustomerCommandRequest request) {
        if (request == null || request.customerName() == null || request.customerName().isBlank()) throw new IllegalArgumentException("客户名称不能为空");
        List<CustomerContractRequest> contracts = request.contracts() == null ? List.of() : request.contracts();
        Set<String> contractNos = new HashSet<>();
        Map<Long,List<DateRange>> ranges = new HashMap<>();
        for (CustomerContractRequest contract : contracts) {
            if (contract == null || contract.contractNo() == null || contract.contractNo().isBlank()) throw new IllegalArgumentException("请填写合同编号");
            if (!contractNos.add(contract.contractNo().trim().toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("同一客户的合同编号不能重复");
            if (contract.startDate() == null || contract.endDate() == null || contract.endDate().isBefore(contract.startDate())) throw new IllegalArgumentException("合同有效期不正确");
            Set<Long> skuIds = new HashSet<>();
            for (CustomerContractPriceRequest price : contract.prices() == null ? List.<CustomerContractPriceRequest>of() : contract.prices()) {
                if (price == null || price.skuId() == null || !skuIds.add(price.skuId())) throw new IllegalArgumentException("同一合同不能重复添加产品");
                Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=? AND enabled=TRUE", Integer.class, price.skuId());
                if (exists == null || exists == 0) throw new IllegalArgumentException("合同产品不存在或已停用");
                if (price.salePrice() == null || price.salePrice().signum() < 0 || price.salePrice().scale() > 4) throw new IllegalArgumentException("合同价格必须是最多四位小数的非负数");
                List<DateRange> skuRanges = ranges.computeIfAbsent(price.skuId(), key -> new ArrayList<>());
                if (skuRanges.stream().anyMatch(range -> !contract.endDate().isBefore(range.start()) && !contract.startDate().isAfter(range.end()))) throw new IllegalArgumentException("同一产品的合同有效期不能重叠");
                skuRanges.add(new DateRange(contract.startDate(), contract.endDate()));
            }
        }
    }
    private record DateRange(LocalDate start, LocalDate end) {}
    private long insert(String sql,Object... args) { var keys=new GeneratedKeyHolder(); jdbc.update(c->{PreparedStatement s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},keys);return Objects.requireNonNull(keys.getKey()).longValue(); }
    private String trim(String value) { return value.trim(); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Map<String,Object> camel(Map<String,Object> source) { Map<String,Object> result=new LinkedHashMap<>(); source.forEach((key,value)->result.put(switch(key.toLowerCase(Locale.ROOT)){case "customercode"->"customerCode";case "customername"->"customerName";case "businesscontactname"->"businessContactName";case "businesscontactphone"->"businessContactPhone";case "ordercontactname"->"orderContactName";case "ordercontactphone"->"orderContactPhone";case "financecontactname"->"financeContactName";case "financecontactphone"->"financeContactPhone";case "invoicetitle"->"invoiceTitle";case "taxpayerid"->"taxpayerId";case "invoiceaddress"->"invoiceAddress";case "invoicephone"->"invoicePhone";case "bankname"->"bankName";case "bankaccount"->"bankAccount";case "contractno"->"contractNo";case "startdate"->"startDate";case "enddate"->"endDate";case "skuid"->"skuId";case "saleprice"->"salePrice";case "skucode"->"skuCode";case "productname"->"productName";default->key;},value)); return result; }
}
