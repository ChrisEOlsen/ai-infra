CREATE TABLE embedding_profile (
 id integer PRIMARY KEY CHECK(id=1),
 provider text NOT NULL,
 model text NOT NULL,
 dimensions integer NOT NULL,
 mock boolean NOT NULL
);
