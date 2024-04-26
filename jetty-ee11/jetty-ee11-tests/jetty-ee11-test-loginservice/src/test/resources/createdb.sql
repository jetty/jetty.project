CREATE TABLE roles
(
    id INT NOT NULL PRIMARY KEY,
    role VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE users 
(
    id INT NOT NULL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    pwd VARCHAR(50) NOT NULL
);

CREATE TABLE user_roles
(
    user_id INT NOT NULL,
    role_id INT NOT NULL,
    UNIQUE(user_id, role_id)
);

INSERT INTO roles VALUES
(0,'user'),
(1,'admin');

INSERT INTO users VALUES
(1,'jetty','MD5:164c88b302622e17050af52c89945d44'),
(2,'admin','CRYPT:adpexzg3FUZAk'),
(3,'other','OBF:1xmk1w261u9r1w1c1xmq'),
(4,'plain','plain'),
(5,'user','password'),
(6,'digest','MD5:6e120743ad67abfbc385bc2bb754e297'),
(7,'dstest','dstest');

INSERT INTO user_roles VALUES
(1,1),
(2,1),
(2,2),
(3,1),
(4,1),
(5,1),
(6,1),
(7,1);
