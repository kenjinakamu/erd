SELECT
    t2.column_name,
    count(*) over (partition by t1.constraint_name) AS column_count
FROM
    information_schema.table_constraints t1
    JOIN information_schema.key_column_usage t2
    ON t1.constraint_name = t2.constraint_name
    AND t1.table_schema = t2.table_schema
    AND t1.table_name = t2.table_name
WHERE
    t1.constraint_type = 'UNIQUE'
    AND t1.table_schema = #{schema}
    AND t1.table_name = #{table}
