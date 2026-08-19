package com.internalops.aftersales;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.numbering.DocumentNumberService;
import com.internalops.numbering.DocumentType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AfterSalesCommandService {
    private final JdbcTemplate jdbc;
    private final DocumentNumberService documentNumbers;
    public AfterSalesCommandService(JdbcTemplate jdbc, DocumentNumberService documentNumbers) { this.jdbc = jdbc; this.documentNumbers = documentNumbers; }

    @Transactional
    public Map<String,Object> create(AfterSalesRequest request) {
        writable(); validateHeader(request);
        Map<String,Object> order = one("SELECT id,customer_id,order_no FROM sales_order WHERE id=? FOR UPDATE", request.orderId());
        if (request.returnLines()==null || request.returnLines().isEmpty()) throw new IllegalArgumentException("至少选择一条退货明细");
        String no = documentNumbers.next(DocumentType.AFTER_SALES, request.applicationDate() == null ? LocalDate.now() : request.applicationDate());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement s=c.prepareStatement("INSERT INTO after_sales_order(after_sales_no,sales_order_id,customer_id,after_sales_type,status,issue_description,application_date,contact_name,contact_phone,delivery_address,remark,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS); int i=1; s.setString(i++,no);s.setLong(i++,request.orderId());s.setLong(i++,num(order,"customer_id"));s.setString(i++,request.replacementLines()!=null&&!request.replacementLines().isEmpty()?"EXCHANGE":"RETURN");s.setString(i++,"WAITING_RETURN");s.setString(i++,request.issueDescription().trim());s.setObject(i++,request.applicationDate()==null?LocalDate.now():request.applicationDate());s.setString(i++,request.contactName());s.setString(i++,request.contactPhone());s.setString(i++,request.deliveryAddress());s.setString(i++,request.remark());s.setObject(i++,userId());s.setObject(i,userId());return s; },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        Map<Long,Long> returnIds=new HashMap<>();
        for (AfterSalesRequest.ReturnLine line:request.returnLines()) {
            if(line.requestedQuantity()<=0) throw new IllegalArgumentException("退回数量必须大于零");
            Map<String,Object> item=one("SELECT i.id,i.sku_id,i.shipped_quantity,s.customer_part_number,s.product_name,s.model,s.configuration,s.unit FROM sales_order_item i JOIN sku s ON s.id=i.sku_id WHERE i.id=? AND i.sales_order_id=? FOR UPDATE",line.salesOrderItemId(),request.orderId());
            Integer used=jdbc.queryForObject("SELECT COALESCE(SUM(r.requested_quantity),0) FROM after_sales_return_line r JOIN after_sales_order a ON a.id=r.after_sales_order_id WHERE r.sales_order_item_id=? AND a.status<>'CANCELLED'",Integer.class,line.salesOrderItemId());
            if(line.requestedQuantity()>((int)num(item,"shipped_quantity")-Objects.requireNonNullElse(used,0))) throw new IllegalArgumentException("退回数量超过该订单明细可退数量");
            GeneratedKeyHolder lineKey=new GeneratedKeyHolder();
            jdbc.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO after_sales_return_line(after_sales_order_id,sales_order_item_id,sku_id,customer_part_number,product_name,model,configuration,unit,requested_quantity) VALUES(?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);s.setLong(1,id);s.setLong(2,line.salesOrderItemId());s.setLong(3,num(item,"sku_id"));s.setString(4,str(item,"customer_part_number"));s.setString(5,str(item,"product_name"));s.setString(6,str(item,"model"));s.setString(7,str(item,"configuration"));s.setString(8,str(item,"unit"));s.setInt(9,line.requestedQuantity());return s;},lineKey);
            returnIds.put(line.salesOrderItemId(),Objects.requireNonNull(lineKey.getKey()).longValue());
        }
        if(request.replacementLines()!=null) for(var line:request.replacementLines()) {
            if(line.plannedQuantity()<=0) throw new IllegalArgumentException("换出数量必须大于零");
            Map<String,Object> sku=one("SELECT id,customer_part_number,product_name,model,configuration,unit FROM sku WHERE id=? AND enabled=TRUE",line.skuId());
            Long returnId=line.returnLineId()!=null?line.returnLineId():returnIds.get(line.salesOrderItemId());
            jdbc.update("INSERT INTO after_sales_replacement_line(after_sales_order_id,return_line_id,sku_id,customer_part_number,product_name,model,configuration,unit,planned_quantity) VALUES(?,?,?,?,?,?,?,?,?)",id,returnId,line.skuId(),str(sku,"customer_part_number"),str(sku,"product_name"),str(sku,"model"),str(sku,"configuration"),str(sku,"unit"),line.plannedQuantity());
        }
        event(id,"CREATED","创建售后单"); return Map.of("id",id,"afterSalesNo",no,"status","WAITING_RETURN","version",0);
    }

    @Transactional
    public Map<String,Object> update(long id,AfterSalesRequest r){
        writable(); Map<String,Object> head=one("SELECT status,version FROM after_sales_order WHERE id=? FOR UPDATE",id);
        if(!"WAITING_RETURN".equals(str(head,"status"))) throw new IllegalStateException("售后已开始办理，不可修改基础信息");
        if(r.version()==null||r.version()!=num(head,"version")) throw new IllegalStateException("数据已被修改，请刷新后重试");
        jdbc.update("UPDATE after_sales_order SET issue_description=?,application_date=?,contact_name=?,contact_phone=?,delivery_address=?,remark=?,updated_by=?,version=version+1 WHERE id=?",r.issueDescription(),r.applicationDate(),r.contactName(),r.contactPhone(),r.deliveryAddress(),r.remark(),userId(),id);
        event(id,"UPDATED","修改售后信息"); return Map.of("id",id,"status","WAITING_RETURN","version",r.version()+1);
    }

    @Transactional public Map<String,Object> cancel(long id,int version){ writable(); Map<String,Object> h=one("SELECT status,version FROM after_sales_order WHERE id=? FOR UPDATE",id); if(version!=num(h,"version"))throw new IllegalStateException("数据已被修改，请刷新后重试"); Integer moved=jdbc.queryForObject("SELECT COALESCE(SUM(received_quantity),0) FROM after_sales_return_line WHERE after_sales_order_id=?",Integer.class,id);Integer sent=jdbc.queryForObject("SELECT COALESCE(SUM(shipped_quantity),0) FROM after_sales_replacement_line WHERE after_sales_order_id=?",Integer.class,id);if(Objects.requireNonNullElse(moved,0)>0||Objects.requireNonNullElse(sent,0)>0)throw new IllegalStateException("已产生库存流水的售后单不可取消");jdbc.update("UPDATE after_sales_order SET status='CANCELLED',version=version+1 WHERE id=?",id);event(id,"CANCELLED","取消售后单");return Map.of("id",id,"status","CANCELLED");}

    @Transactional public Map<String,Object> receive(long id,AfterSalesReceiptRequest r){
        writable(); Map<String,Object> h=lockVersion(id,r.version(),Set.of("WAITING_RETURN","RETURN_RECEIVED")); long wh=defaultWarehouse();
        for(var input:r.items()){if(input.goodQuantity()<0||input.defectiveQuantity()<0||input.goodQuantity()+input.defectiveQuantity()<=0)throw new IllegalArgumentException("本次收货数量必须大于零");Map<String,Object> line=one("SELECT id,sku_id,requested_quantity,received_quantity FROM after_sales_return_line WHERE id=? AND after_sales_order_id=? FOR UPDATE",input.returnLineId(),id);int amount=input.goodQuantity()+input.defectiveQuantity();if(num(line,"received_quantity")+amount>num(line,"requested_quantity"))throw new IllegalArgumentException("收货数量超过待收数量");jdbc.update("UPDATE after_sales_return_line SET received_quantity=received_quantity+?,good_quantity=good_quantity+?,defective_quantity=defective_quantity+?,version=version+1 WHERE id=?",amount,input.goodQuantity(),input.defectiveQuantity(),input.returnLineId());if(input.goodQuantity()>0) inbound(wh,num(line,"sku_id"),input.goodQuantity(),str(h,"after_sales_no"));}
        int pending=jdbc.queryForObject("SELECT COUNT(*) FROM after_sales_return_line WHERE after_sales_order_id=? AND received_quantity<requested_quantity",Integer.class,id);int replacements=jdbc.queryForObject("SELECT COUNT(*) FROM after_sales_replacement_line WHERE after_sales_order_id=?",Integer.class,id);String status=pending>0?"WAITING_RETURN":replacements>0?"WAITING_REPLACEMENT":"COMPLETED";jdbc.update("UPDATE after_sales_order SET status=?,version=version+1 WHERE id=?",status,id);event(id,"RETURN_RECEIVED","确认退货收货");return Map.of("id",id,"status",status);
    }

    @Transactional public Map<String,Object> ship(long id,AfterSalesShipmentRequest r){
        writable(); if(r.trackingNo()==null||r.trackingNo().isBlank())throw new IllegalArgumentException("物流单号不能为空");Map<String,Object> h=lockVersion(id,r.version(),Set.of("WAITING_REPLACEMENT"));long wh=defaultWarehouse();List<AfterSalesShipmentRequest.Item> inputs=new ArrayList<>(r.items());inputs.sort(Comparator.comparingLong(AfterSalesShipmentRequest.Item::replacementLineId));for(var input:inputs){if(input.quantity()<=0)throw new IllegalArgumentException("发货数量必须大于零");Map<String,Object> line=one("SELECT id,sku_id,planned_quantity,shipped_quantity FROM after_sales_replacement_line WHERE id=? AND after_sales_order_id=? FOR UPDATE",input.replacementLineId(),id);if(num(line,"shipped_quantity")+input.quantity()>num(line,"planned_quantity"))throw new IllegalArgumentException("发货数量超过待发数量");outbound(wh,num(line,"sku_id"),input.quantity(),str(h,"after_sales_no"));jdbc.update("UPDATE after_sales_replacement_line SET shipped_quantity=shipped_quantity+?,version=version+1 WHERE id=?",input.quantity(),input.replacementLineId());}int pending=jdbc.queryForObject("SELECT COUNT(*) FROM after_sales_replacement_line WHERE after_sales_order_id=? AND shipped_quantity<planned_quantity",Integer.class,id);String status=pending==0?"COMPLETED":"WAITING_REPLACEMENT";jdbc.update("UPDATE after_sales_order SET status=?,version=version+1 WHERE id=?",status,id);event(id,"REPLACEMENT_SHIPPED","换货发出："+r.carrier()+" "+r.trackingNo()+"，日期 "+Objects.requireNonNullElse(r.shipmentDate(),LocalDate.now()));return Map.of("id",id,"status",status);}

    private void inbound(long wh,long sku,int qty,String no){jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity) VALUES(?,?,0,0,0) ON DUPLICATE KEY UPDATE sku_id=VALUES(sku_id)",wh,sku);Map<String,Object>b=one("SELECT id,actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE",wh,sku);int a=(int)num(b,"actual_quantity"),l=(int)num(b,"locked_quantity"),t=(int)num(b,"in_transit_quantity");jdbc.update("UPDATE inventory_balance SET actual_quantity=actual_quantity+?,version=version+1 WHERE id=?",qty,num(b,"id"));tx(wh,sku,"AFTER_SALES_RETURN",no,qty,a,a+qty,l,l,t,t);}
    private void outbound(long wh,long sku,int qty,String no){Map<String,Object>b=one("SELECT id,actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE",wh,sku);int a=(int)num(b,"actual_quantity"),l=(int)num(b,"locked_quantity"),t=(int)num(b,"in_transit_quantity");if(a-l<qty)throw new IllegalStateException("换出商品可用库存不足");jdbc.update("UPDATE inventory_balance SET actual_quantity=actual_quantity-?,version=version+1 WHERE id=?",qty,num(b,"id"));tx(wh,sku,"AFTER_SALES_REPLACEMENT",no,-qty,a,a-qty,l,l,t,t);}
    private void tx(long wh,long sku,String type,String no,int delta,int ab,int aa,int lb,int la,int tb,int ta){jdbc.update("INSERT INTO inventory_transaction(warehouse_id,sku_id,transaction_type,business_type,business_no,actual_delta,locked_delta,transit_delta,actual_before,actual_after,locked_before,locked_after,transit_before,transit_after) VALUES(?,?,?,'AFTER_SALES',?,?,0,0,?,?,?,?,?,?)",wh,sku,type,no,delta,ab,aa,lb,la,tb,ta);}
    private Map<String,Object> lockVersion(long id,int version,Set<String> allowed){Map<String,Object> h=one("SELECT after_sales_no,status,version FROM after_sales_order WHERE id=? FOR UPDATE",id);if(num(h,"version")!=version)throw new IllegalStateException("数据已被修改，请刷新后重试");if(!allowed.contains(str(h,"status")))throw new IllegalStateException("当前售后状态不可办理此操作");return h;}
    private long defaultWarehouse(){return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1",Long.class));}
    private void event(long id,String type,String desc){jdbc.update("INSERT INTO after_sales_event(after_sales_order_id,event_type,description,operated_by) VALUES(?,?,?,?)",id,type,desc,userId());}
    private void validateHeader(AfterSalesRequest r){if(r.issueDescription()==null||r.issueDescription().isBlank())throw new IllegalArgumentException("问题描述不能为空");}
    private void writable(){CurrentUser u=CurrentUser.get();if(u!=null&&u.role()==UserRole.FINANCE)throw new IllegalStateException("财务用户仅可查看售后记录");}
    private Long userId(){return CurrentUser.get()==null?null:CurrentUser.get().id();}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new IllegalArgumentException("记录不存在");return rows.get(0);}
    static long num(Map<String,Object> m,String key){for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(key))return ((Number)e.getValue()).longValue();throw new IllegalArgumentException("缺少字段 "+key);}
    static String str(Map<String,Object> m,String key){for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue()==null?null:String.valueOf(e.getValue());return null;}
}
