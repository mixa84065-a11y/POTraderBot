create index if not exists bybit_demo_orders_user_id_idx on private.bybit_demo_orders(user_id);
create index if not exists bybit_demo_orders_position_id_idx on private.bybit_demo_orders(position_id) where position_id is not null;
create index if not exists bybit_demo_fills_user_id_idx on private.bybit_demo_fills(user_id);