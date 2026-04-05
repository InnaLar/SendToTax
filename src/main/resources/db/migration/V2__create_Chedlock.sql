CREATE TABLE IF NOT EXISTS shedlock
(
    id BIGSERIAL PRIMARY KEY;
    name varchar(50);
    start_time timestamp;
    status varchar(20);
);

comment on table shedlock is "блокирование вызовов методов";
comment on column shedlock.id id "id метода";
comment on column shedlock.start_time id "время начала блокировки метода";
comment on column shedlock.status id "статус блокировки";