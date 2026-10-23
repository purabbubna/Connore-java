-- Connore schema from the supplied developer guide. MySQL 8.0.16+.
CREATE DATABASE IF NOT EXISTS connore CHARACTER SET utf8mb4;
USE connore;
CREATE TABLE account (
   account_id     INT AUTO_INCREMENT PRIMARY KEY,
   college_email VARCHAR(100) NOT NULL UNIQUE,
   password_hash VARCHAR(255) NOT NULL,
   email_verified BOOLEAN NOT NULL DEFAULT FALSE,
   created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE student_profile (
  student_id      INT PRIMARY KEY,
     full_name            VARCHAR(100) NOT NULL,
     department           VARCHAR(60),
     year_of_study        TINYINT,
     bio                  VARCHAR(500),
     show_department BOOLEAN NOT NULL DEFAULT FALSE,
     show_year       BOOLEAN NOT NULL DEFAULT FALSE,
     suggestible     BOOLEAN NOT NULL DEFAULT TRUE,
     FOREIGN KEY (student_id) REFERENCES account(account_id) ON DELETE CASCADE
);
CREATE TABLE social_link (
  link_id    INT AUTO_INCREMENT PRIMARY KEY,
     student_id INT NOT NULL,
     platform   VARCHAR(30) NOT NULL,
     handle           VARCHAR(100) NOT NULL,
     shared           BOOLEAN NOT NULL DEFAULT FALSE,
  FOREIGN KEY (student_id) REFERENCES student_profile(student_id) ON DELETE
CASCADE
);
CREATE TABLE category (
  category_id INT AUTO_INCREMENT PRIMARY KEY,
     name             VARCHAR(50) NOT NULL UNIQUE
);
CREATE TABLE interest (
     interest_id INT AUTO_INCREMENT PRIMARY KEY,
     name        VARCHAR(80) NOT NULL UNIQUE,
     category_id INT NOT NULL,
     is_approved BOOLEAN NOT NULL DEFAULT TRUE,
     FOREIGN KEY (category_id) REFERENCES category(category_id)
);
CREATE TABLE student_interest (
     student_id INT NOT NULL,
     interest_id INT NOT NULL,
     added_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     PRIMARY KEY (student_id, interest_id),
     INDEX idx_student_interest_interest (interest_id),
     FOREIGN KEY (student_id) REFERENCES student_profile(student_id) ON DELETE
CASCADE,
     FOREIGN KEY (interest_id) REFERENCES interest(interest_id)
);
CREATE TABLE community (
     community_id INT AUTO_INCREMENT PRIMARY KEY,
     interest_id INT NOT NULL UNIQUE,
     description      VARCHAR(300),
     created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     FOREIGN KEY (interest_id) REFERENCES interest(interest_id)
);
CREATE TABLE community_membership (
     student_id   INT NOT NULL,
     community_id INT NOT NULL,
     joined_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     PRIMARY KEY (student_id, community_id),
     INDEX idx_membership_community (community_id),
     FOREIGN KEY (student_id)   REFERENCES student_profile(student_id) ON DELETE
CASCADE,
  FOREIGN KEY (community_id) REFERENCES community(community_id) ON DELETE
CASCADE
);
CREATE TABLE `connection` (
     connection_id          INT AUTO_INCREMENT PRIMARY KEY,
     requester_id           INT NOT NULL,
     receiver_id         INT NOT NULL,
     context_interest_id INT NULL,
     status                 VARCHAR(10) NOT NULL,
     created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  student_low_id        INT GENERATED ALWAYS AS (LEAST(requester_id, receiver_id))
STORED,
  student_high_id INT GENERATED ALWAYS AS (GREATEST(requester_id,
receiver_id)) STORED,
     CONSTRAINT chk_connection_pair   CHECK (requester_id <> receiver_id),
     CONSTRAINT chk_connection_status CHECK (status IN
('PENDING','ACCEPTED','CLOSED','BLOCKED')),
  UNIQUE KEY uq_connection_pair (student_low_id, student_high_id),
     INDEX idx_connection_requester (requester_id),
     INDEX idx_connection_receiver (receiver_id),
     FOREIGN KEY (requester_id)           REFERENCES student_profile(student_id),
     FOREIGN KEY (receiver_id)            REFERENCES student_profile(student_id),
     FOREIGN KEY (context_interest_id) REFERENCES interest(interest_id)
);
CREATE TABLE pending_request (
  connection_id INT PRIMARY KEY,
     expires_at    DATETIME NOT NULL,
     INDEX idx_pending_expires (expires_at),
  FOREIGN KEY (connection_id) REFERENCES `connection`(connection_id) ON
DELETE CASCADE
);
CREATE TABLE accepted_connection (
  connection_id INT PRIMARY KEY,
     connected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     FOREIGN KEY (connection_id) REFERENCES `connection`(connection_id) ON
DELETE CASCADE
);
CREATE TABLE closed_request (
     connection_id INT PRIMARY KEY,
     outcome       VARCHAR(10) NOT NULL,
     closed_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     CONSTRAINT chk_closed_outcome CHECK (outcome IN ('DECLINED','EXPIRED')),
  FOREIGN KEY (connection_id) REFERENCES `connection`(connection_id) ON
DELETE CASCADE
);
CREATE TABLE `block` (
  connection_id INT PRIMARY KEY,
     blocked_by       INT NOT NULL,
     blocked_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (connection_id) REFERENCES `connection`(connection_id) ON
DELETE CASCADE,
     FOREIGN KEY (blocked_by)      REFERENCES student_profile(student_id)
);
