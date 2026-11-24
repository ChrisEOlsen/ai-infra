CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE collections(id uuid PRIMARY KEY, name text NOT NULL UNIQUE, roles text[] NOT NULL CHECK(cardinality(roles)>0));
CREATE TABLE sources(id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES collections(id), bucket text NOT NULL, prefix text NOT NULL, status text NOT NULL DEFAULT 'Pending', error text, synced_at timestamptz, UNIQUE(bucket,prefix));
CREATE TABLE documents(id uuid PRIMARY KEY, collection_id uuid NOT NULL REFERENCES collections(id), source_id uuid REFERENCES sources(id) ON DELETE CASCADE, source_key text, version text, name text NOT NULL, path text NOT NULL, status text NOT NULL DEFAULT 'Queued', error text, updated_at timestamptz NOT NULL DEFAULT now(), lease_at timestamptz, UNIQUE(source_id,source_key));
CREATE TABLE chunks(id bigserial PRIMARY KEY, document_id uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE, ordinal integer NOT NULL, content text NOT NULL, embedding vector NOT NULL, embedding_model text NOT NULL);
CREATE INDEX chunks_document ON chunks(document_id);
CREATE INDEX documents_status ON documents(status);
CREATE TABLE app_state(id integer PRIMARY KEY CHECK(id=1), accepting boolean NOT NULL DEFAULT true);
INSERT INTO app_state(id) VALUES(1);
