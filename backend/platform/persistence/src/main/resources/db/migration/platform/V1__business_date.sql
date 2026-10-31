-- Shared business date for the demo clock (seed.clock.mode=database). One row; id is always 1.
CREATE TABLE platform.business_date (
    id            smallint    PRIMARY KEY CHECK (id = 1),
    business_date date        NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
