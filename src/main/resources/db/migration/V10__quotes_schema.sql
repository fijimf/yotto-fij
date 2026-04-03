CREATE TABLE quotes (
    id          BIGSERIAL PRIMARY KEY,
    quote_text  TEXT         NOT NULL,
    attribution VARCHAR(500) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true
);
