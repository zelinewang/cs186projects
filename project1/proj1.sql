-- Before running drop any existing views
DROP VIEW IF EXISTS q0;
DROP VIEW IF EXISTS q1i;
DROP VIEW IF EXISTS q1ii;
DROP VIEW IF EXISTS q1iii;
DROP VIEW IF EXISTS q1iv;
DROP VIEW IF EXISTS q2i;
DROP VIEW IF EXISTS q2ii;
DROP VIEW IF EXISTS q2iii;
DROP VIEW IF EXISTS q3i;
DROP VIEW IF EXISTS q3ii;
DROP VIEW IF EXISTS q3iii;
DROP VIEW IF EXISTS q4i;
DROP VIEW IF EXISTS q4ii;
DROP VIEW IF EXISTS q4iii;
DROP VIEW IF EXISTS q4iv;
DROP VIEW IF EXISTS q4v;

-- Question 0
CREATE VIEW q0(era)
AS
  SELECT MAX(era)
  FROM pitching
;

-- Question 1i
CREATE VIEW q1i(namefirst, namelast, birthyear)
AS
  SELECT namefirst, namelast, birthyear
  FROM people
  WHERE weight > 300
;

-- Question 1ii
CREATE VIEW q1ii(namefirst, namelast, birthyear)
AS
  SELECT namefirst, namelast, birthyear
  FROM people
  WHERE namefirst LIKE '% %'
;

-- Question 1iii
CREATE VIEW q1iii(birthyear, avgheight, count)
AS
  SELECT birthyear, AVG(height), COUNT(*)
  FROM people
  GROUP BY birthyear
  ORDER BY birthyear
  -- use * but not COUNT(birthyear) cuz we are also counting all the players with NULL birthyear
;

-- Question 1iv
CREATE VIEW q1iv(birthyear, avgheight, count)
AS
  SELECT birthyear, AVG(height), COUNT(*)
  FROM people
  GROUP BY birthyear
  HAVING AVG(height) > 70
  ORDER BY birthyear
;

-- Question 2i
CREATE VIEW q2i(namefirst, namelast, playerid, yearid)
AS
  SELECT p.namefirst, p.namelast, h.playerid, h.yearid
  FROM HallOfFame AS h
  INNER JOIN people AS p
  ON h.playerid = p.playerid
  WHERE h.inducted LIKE 'Y'
  ORDER BY h.yearid DESC, h.playerid ASC
;

-- Question 2ii (really good one!!!!!)
CREATE VIEW q2ii(namefirst, namelast, playerid, schoolid, yearid)
AS
  SELECT n.namefirst, n.namelast, n.playerid, c.schoolid, n.yearid
  FROM (
    SELECT * FROM q2i
  ) AS n
  INNER JOIN CollegePlaying as c
  ON n.playerid = c.playerid
  WHERE EXISTS (
    SELECT *
    FROM Schools as s
    WHERE c.schoolid = s.schoolid AND s.schoolState LIKE 'CA'
  )
  ORDER BY n.yearid DESC, c.schoolid, n.playerid

  -- Could change ' SELECT * FROM q2i'
  -- TO:
  -- SELECT p.namefirst, p.namelast, h.playerid, h.yearid
  -- FROM HallOfFame AS h
  -- INNER JOIN people AS p
  -- ON h.playerid = p.playerid
  -- WHERE h.inducted LIKE 'Y'
;

-- Question 2iii
CREATE VIEW q2iii(playerid, namefirst, namelast, schoolid)
AS
  SELECT  n.playerid, n.namefirst, n.namelast, c.schoolid
  FROM (
    SELECT p.namefirst, p.namelast, h.playerid
    FROM HallOfFame AS h
    INNER JOIN people AS p
    ON h.playerid = p.playerid
    WHERE h.inducted LIKE 'Y'
  ) AS n
  LEFT JOIN CollegePlaying as c
  ON n.playerid = c.playerid
  ORDER BY n.playerid DESC, c.schoolid
;

-- Question 3i
CREATE VIEW q3i(playerid, namefirst, namelast, yearid, slg)
AS
  SELECT n.playerid, p.namefirst, p.namelast, n.yearid, n.slg
  FROM people as p
  INNER JOIN (
    SELECT playerid, yearid, (H * 1.0 + H2B * 1.0 + H3B * 2.0 + HR * 3.0)/AB as slg
    FROM Batting
    WHERE AB > 50
    GROUP BY playerid, yearid, teamid
    ORDER BY slg DESC
    LIMIT 10
  ) as n
  ON p.playerid = n.playerid
  ORDER BY n.slg DESC, n.yearid, n.playerid
;

-- Question 3ii
CREATE VIEW q3ii(playerid, namefirst, namelast, lslg)
AS
  SELECT n.playerid, p.namefirst, p.namelast, n.lslg
  FROM people as p
  INNER JOIN (
    SELECT playerid, (SUM(H) * 1.0 + SUM(H2B) * 1.0 + SUM(H3B) * 2.0 + SUM(HR) * 3.0)/SUM(AB) as lslg
    FROM Batting
    GROUP BY playerid
    HAVING SUM(AB) > 50
    ORDER BY lslg DESC, playerid
    LIMIT 10
  ) as n
  ON p.playerid = n.playerid
;

-- Question 3iii

--METHOD 1

--DROP VIEW IF EXISTS q3iiiHelper
--CREATE VIEW q3iii(namefirst, namelast, lslg)
--AS
--  SELECT p.namefirst, p.namelast, n.lslg
--  FROM people as p
--  INNER JOIN q3iiiHelper as n
--  ON p.playerid = n.playerid
--  WHERE n.lslg > (
--    SELECT lslg
--    FROM q3iiiHelper      --WE can't use n in the subqueries cuz 'n' was defined in upper query
--    WHERE playerid LIKE 'mayswi01'
--  )
--;

--CREATE VIEW q3iiiHelper(playerid, lslg)
--AS
--  SELECT playerid, (SUM(H) * 1.0 + SUM(H2B) * 1.0 + SUM(H3B) * 2.0 + SUM(HR) * 3.0)/SUM(AB) as lslg
--  FROM Batting
--  GROUP BY playerid
--  HAVING SUM(AB) > 50

-- METHOD 2
CREATE VIEW q3iii(namefirst, namelast, lslg)
AS
  SELECT p.namefirst, p.namelast, n.lslg
  FROM people as p
  INNER JOIN (
    SELECT playerid, (SUM(H) * 1.0 + SUM(H2B) * 1.0 + SUM(H3B) * 2.0 + SUM(HR) * 3.0)/SUM(AB) as lslg
    FROM Batting
    GROUP BY playerid
    HAVING SUM(AB) > 50
  ) as n
  ON p.playerid = n.playerid
  WHERE n.lslg > (
    SELECT (SUM(H) * 1.0 + SUM(H2B) * 1.0 + SUM(H3B) * 2.0 + SUM(HR) * 3.0)/SUM(AB)
    FROM Batting
    WHERE playerid LIKE "mayswi01"
  )
;
;

-- Question 4i
CREATE VIEW q4i(yearid, min, max, avg)
AS
  SELECT yearid, MIN(salary) as min, MAX(salary) as max, avg(salary) as avg
  FROM Salaries
  GROUP BY yearid
  ORDER BY yearid
;

-- Question 4ii
CREATE VIEW q4ii(binid, low, high, count)
AS
  SELECT binid, s.min+binid*((s.max - s.min)/10.0), s.min+(binid+1)*((s.max - s.min)/10.0), COUNT(*)
  FROM q4iiHelper as s
  INNER JOIN binids as b
  ON b.binid = CAST((s.salary - s.min)/((s.max - s.min)/10.0) AS INT)
  OR (b.binid + 1 = CAST((s.salary - s.min)/((s.max - s.min)/10.0) AS INT)
  AND CAST((s.salary - s.min)/((s.max - s.min)/10.0) AS INT) = 10) -- for the 9 max boundary
  GROUP BY b.binid
;

DROP VIEW IF EXISTS q4iiHelper;
CREATE VIEW q4iiHelper(salary, min, max, avg)
AS
  SELECT s.salary, n.min, n.max, n.avg
  FROM (
    SELECT salary
    FROM Salaries
    WHERE yearid LIKE '2016'
    ) as s
  INNER JOIN (
    SELECT MIN(salary) as min, MAX(salary) as max, avg(salary) as avg
    FROM Salaries
    WHERE yearid LIKE '2016'
  ) as n
;


-- Question 4iii
CREATE VIEW q4iii(yearid, mindiff, maxdiff, avgdiff)
AS
  SELECT a.yearid, a.min - b.min, a.max - b.max, a.avg - b.avg
  FROM q4i as a
  LEFT JOIN q4i as b
  ON a.yearid = b.yearid + 1
  WHERE a.yearid > b.yearid -- exclude the situation that b is NULL
  ORDER by a.yearid
;

-- Question 4iv
CREATE VIEW q4iv(playerid, namefirst, namelast, salary, yearid)
AS
  SELECT n.playerid, p.namefirst, p.namelast, n.salary, n.yearid
  FROM people as p
  INNER JOIN (
    SELECT Salaries.yearid, Salaries.salary, Salaries.playerid
    FROM Salaries INNER JOIN q4i
    ON Salaries.yearid = q4i.yearid AND Salaries.salary >= q4i.max
    WHERE Salaries.yearid LIKE '2000' OR Salaries.yearid LIKE '2001'
    -- can't use LIKE '2000' OR '2001', must be separated
  ) as n
  ON p.playerid = n.playerid
;

-- Question 4v
CREATE VIEW q4v(team, diffAvg) AS
  SELECT a.teamid, MAX(s.salary) - MIN(s.salary) AS diffAvg
  FROM AllstarFull as a
  INNER JOIN Salaries as s
  ON a.playerid = s.playerid AND a.yearid = s.yearid AND a.teamid = s.teamid
  WHERE a.yearid LIKE '2016'
  GROUP BY a.teamid
;

