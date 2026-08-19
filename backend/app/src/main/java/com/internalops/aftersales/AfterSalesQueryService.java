package com.internalops.aftersales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service public class AfterSalesQueryService {
 private final JdbcTemplate jdbc; public AfterSalesQueryService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Map<String,Object> get(long id){
  Map<String,Object> result=new LinkedHashMap<>(camel(one("SELECT a.*,o.order_no,o.order_type,c.customer_name FROM after_sales_order a JOIN sales_order o ON o.id=a.sales_order_id JOIN customer c ON c.id=a.customer_id WHERE a.id=?",id)));
  result.put("returnLines",jdbc.queryForList("SELECT r.*,s.product_code,s.customer_part_number,s.model FROM after_sales_return_line r JOIN sku s ON s.id=r.sku_id WHERE r.after_sales_order_id=? ORDER BY r.id",id).stream().map(this::camel).toList());
  result.put("replacementLines",jdbc.queryForList("SELECT r.*,s.product_code,s.customer_part_number,s.model,COALESCE((SELECT SUM(b.actual_quantity-b.locked_quantity) FROM inventory_balance b WHERE b.sku_id=r.sku_id),0) available_quantity FROM after_sales_replacement_line r JOIN sku s ON s.id=r.sku_id WHERE r.after_sales_order_id=? ORDER BY r.id",id).stream().map(this::camel).toList());
  result.put("events",jdbc.queryForList("SELECT event_type,description,operated_at FROM after_sales_event WHERE after_sales_order_id=? ORDER BY operated_at,id",id).stream().map(this::camel).toList());
  return result;
 }
 public Map<String,Object> refundSuggestion(long id){
  Map<String,Object> afterSales=one("SELECT id,after_sales_no,customer_id,after_sales_type,status FROM after_sales_order WHERE id=?",id);
  if(!"RETURN".equals(String.valueOf(afterSales.get("after_sales_type"))))throw new IllegalStateException("只有退货售后单可以申请退款");
  if(!"RETURN_RECEIVED".equals(String.valueOf(afterSales.get("status"))))throw new IllegalStateException("请先确认收到退货再申请退款");
  BigDecimal suggested=jdbc.queryForObject("SELECT COALESCE(SUM(r.received_quantity*i.sale_price),0) FROM after_sales_return_line r JOIN sales_order_item i ON i.id=r.sales_order_item_id WHERE r.after_sales_order_id=?",BigDecimal.class,id);
  Map<String,Object> result=new LinkedHashMap<>(camel(afterSales));
  result.put("suggestedAmount",suggested==null?BigDecimal.ZERO:suggested);
  return result;
 }
 public List<Map<String,Object>> orderOptions(String keyword){String k=keyword==null?"":keyword.trim();return jdbc.queryForList("SELECT o.id,o.order_no,o.order_type,c.customer_name FROM sales_order o JOIN customer c ON c.id=o.customer_id JOIN sales_order_item i ON i.sales_order_id=o.id WHERE i.shipped_quantity>0 AND (LOCATE(?,o.order_no)>0 OR LOCATE(?,c.customer_name)>0) GROUP BY o.id,o.order_no,o.order_type,c.customer_name,o.updated_at ORDER BY o.updated_at DESC LIMIT 30",k,k).stream().map(this::camel).toList();}
 public List<Map<String,Object>> orderLines(long orderId){return jdbc.queryForList("SELECT i.id sales_order_item_id,i.sku_id,s.product_code,s.customer_part_number,s.product_name,s.model,s.configuration,s.unit,i.shipped_quantity-COALESCE((SELECT SUM(r.requested_quantity) FROM after_sales_return_line r JOIN after_sales_order a ON a.id=r.after_sales_order_id WHERE r.sales_order_item_id=i.id AND a.status<>'CANCELLED'),0) available_return_quantity FROM sales_order_item i JOIN sku s ON s.id=i.sku_id WHERE i.sales_order_id=? AND i.shipped_quantity>0",orderId).stream().map(this::camel).toList();}
 private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new IllegalArgumentException("售后单不存在");return r.get(0);}private Map<String,Object> camel(Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>();row.forEach((k,v)->{String[] p=k.toLowerCase().split("_");StringBuilder n=new StringBuilder(p[0]);for(int i=1;i<p.length;i++)n.append(Character.toUpperCase(p[i].charAt(0))).append(p[i].substring(1));out.put(n.toString(),v);});return out;}
}
