ALTER TABLE site_problems
    ADD "key" VARCHAR(255) NOT NULL;

CREATE INDEX site_problems_key ON site_problems ("key");

ALTER TABLE site_problems
    ADD CONSTRAINT site_problems_item_id_unique UNIQUE (item_id);