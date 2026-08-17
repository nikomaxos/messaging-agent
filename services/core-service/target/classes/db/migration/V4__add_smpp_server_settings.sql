CREATE TABLE smpp_server_settings (
    id BIGINT PRIMARY KEY,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    max_connections INT NOT NULL,
    enquire_link_timeout INT NOT NULL
);

INSERT INTO smpp_server_settings (id, host, port, max_connections, enquire_link_timeout)
VALUES (1, '0.0.0.0', 2775, 50, 30000);
