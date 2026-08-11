package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class InventoryAllocationService {
 private final JdbcTemplate jdbc; public InventoryAllocationService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public String allocate(long orderId){
  long warehouse=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1",Long.class));
  var items=jdbc.queryForList("SELECT id,sku_id,quantity,locked_quantity FROM sales_order_item WHERE sales_order_id=? ORDER BY line_no",orderId);boolean ready=true;
  for(var item:items){long itemId=num(item,"id"),sku=num(item,"sku_id");int qty=(int)num(item,"quantity"),already=(int)num(item,"locked_quantity");int need=Math.max(0,qty-already);
   jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity) VALUES(?,?,0,0,0) ON DUPLICATE KEY UPDATE sku_id=VALUES(sku_id)",warehouse,sku);
   var bal=jdbc.queryForMap("SELECT id,actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE",warehouse,sku);int actual=(int)num(bal,"actual_quantity"),locked=(int)num(bal,"locked_quantity");int take=Math.min(need,Math.max(0,actual-locked));
   if(take>0){jdbc.update("UPDATE inventory_balance SET locked_quantity=locked_quantity+?,version=version+1 WHERE id=?",take,num(bal,"id"));jdbc.update("UPDATE sales_order_item SET locked_quantity=locked_quantity+?,uncovered_quantity=?,version=version+1 WHERE id=?",take,need-take,itemId);tx(warehouse,sku,"ALLOCATE","SALES_ORDER",String.valueOf(orderId),0,take,0,actual,actual,locked,locked+take,(int)num(bal,"in_transit_quantity"),(int)num(bal,"in_transit_quantity"));}
   else jdbc.update("UPDATE sales_order_item SET uncovered_quantity=?,version=version+1 WHERE id=?",need,itemId);
   if(need-take>0)ready=false;
  }
  String status=ready?"READY_TO_SHIP":"WAITING_STOCK";jdbc.update("UPDATE sales_order SET status=?,version=version+1 WHERE id=?",status,orderId);return status;
 }
 public void releaseAll(long orderId,String transactionType){
  long warehouse=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1",Long.class));
  var items=jdbc.queryForList("SELECT id,sku_id,quantity,shipped_quantity,locked_quantity FROM sales_order_item WHERE sales_order_id=? ORDER BY line_no FOR UPDATE",orderId);
  for(var item:items){
   int shipped=(int)num(item,"shipped_quantity"),lockedByOrder=(int)num(item,"locked_quantity");
   if(shipped>0)throw new IllegalStateException("已发货订单不可修改");
   if(lockedByOrder<=0)continue;
   long sku=num(item,"sku_id");
   var bal=jdbc.queryForMap("SELECT id,actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE",warehouse,sku);
   int actual=(int)num(bal,"actual_quantity"),locked=(int)num(bal,"locked_quantity"),transit=(int)num(bal,"in_transit_quantity");
   if(locked<lockedByOrder)throw new IllegalStateException("库存锁定数据异常，请刷新后重试");
   jdbc.update("UPDATE inventory_balance SET locked_quantity=locked_quantity-?,version=version+1 WHERE id=?",lockedByOrder,num(bal,"id"));
   jdbc.update("UPDATE sales_order_item SET locked_quantity=0,uncovered_quantity=GREATEST(0,quantity-shipped_quantity),version=version+1 WHERE id=?",num(item,"id"));
   tx(warehouse,sku,transactionType,"SALES_ORDER",String.valueOf(orderId),0,-lockedByOrder,0,actual,actual,locked,locked-lockedByOrder,transit,transit);
  }
 } public void reallocateWaiting(){for(Long id:jdbc.queryForList("SELECT id FROM sales_order WHERE status='WAITING_STOCK' ORDER BY receipt_confirmed_at,id",Long.class))allocate(id);}
 void tx(long wh,long sku,String type,String business,String no,int ad,int ld,int td,int ab,int aa,int lb,int la,int tb,int ta){jdbc.update("INSERT INTO inventory_transaction(warehouse_id,sku_id,transaction_type,business_type,business_no,actual_delta,locked_delta,transit_delta,actual_before,actual_after,locked_before,locked_after,transit_before,transit_after) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",wh,sku,type,business,no,ad,ld,td,ab,aa,lb,la,tb,ta);}
 static long num(Map<String,Object> m,String key){for(var e:m.entrySet())if(e.getKey().equalsIgnoreCase(key))return ((Number)e.getValue()).longValue();throw new IllegalArgumentException("缺少字段"+key);}
}
