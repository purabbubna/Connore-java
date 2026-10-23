-- Run once in MySQL Workbench after sql/schema.sql.
-- These implement the stored-program examples in report section 12.
USE connore;

DELIMITER $$

CREATE FUNCTION fn_shared_interests(a INT, b INT)
RETURNS INT
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
    DECLARE n INT;
    SELECT COUNT(*) INTO n
    FROM student_interest x
    JOIN student_interest y USING (interest_id)
    WHERE x.student_id = a AND y.student_id = b;
    RETURN n;
END$$

CREATE PROCEDURE sp_student_communities(IN sid INT)
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
    SELECT c.community_id, i.name, m.joined_at
    FROM community_membership m
    JOIN community c USING (community_id)
    JOIN interest i USING (interest_id)
    WHERE m.student_id = sid;
END$$

CREATE TRIGGER trg_profile_year_insert
BEFORE INSERT ON student_profile
FOR EACH ROW
BEGIN
    IF NEW.year_of_study IS NOT NULL
       AND NEW.year_of_study NOT BETWEEN 1 AND 6 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid study year';
    END IF;
END$$

DELIMITER ;

-- Examples (replace IDs with actual student IDs):
-- SELECT fn_shared_interests(1, 2);
-- CALL sp_student_communities(1);
-- SHOW TRIGGERS FROM connore;
