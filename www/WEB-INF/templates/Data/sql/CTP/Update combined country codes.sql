
-- Update country codes and names in combined
/*select distinct country from combined where country like '%,%';

UPDATE country SET name = 'South Korea' WHERE name = 'Korea, South';
update combined



SELECT * FROM combined;
SELECT * FROM combined where country LIKE 'Taiwan%';

SELECT distinct country FROM combined WHERE countrycode is null;*/

UPDATE combined
SET countrycode = v1.countrycode
FROM( SELECT * FROM country ) as v1
WHERE combined.country = v1.name;



UPDATE combined SET country = 'Taiwan' WHERE country = 'Taiwan*';
UPDATE combined SET country = 'South Korea' WHERE country = 'Korea, South';

