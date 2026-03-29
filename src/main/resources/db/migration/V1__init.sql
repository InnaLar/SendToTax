CREATE TABLE IF NOT EXISTS receipts
(
    id        BIGSERIAL PRIMARY KEY,
    processed BOOLEAN DEFAULT FALSE,
    sum       VARCHAR(20),
    source    VARCHAR(10)
);
comment on table receipts is 'Платежки';
comment on column receipts.id is 'id';
comment on column receipts.processed is 'Признак обработки';
comment on column receipts.sum is 'Сумма платежки';
comment on column receipts.source is 'Назначение платежа';

CREATE TABLE IF NOT EXISTS booked
(
    job_id       VARCHAR(36),
    booked_id        BIGSERIAL
);

comment on table booked is 'Занятые id из платежек';
comment on column booked.job_id is 'GUID сервиса';
comment on column booked.booked_id is 'занятые id платежек';