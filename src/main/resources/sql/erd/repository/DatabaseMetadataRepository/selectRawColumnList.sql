SELECT
    column_name AS name,
    CASE
        WHEN data_type IS NULL OR btrim(data_type) = '' THEN 'unknown'
        WHEN udt_name IS NULL OR btrim(udt_name) = '' THEN data_type
        WHEN upper(data_type) IN ('USER-DEFINED', 'ARRAY') THEN udt_name
        ELSE data_type
    END AS type,
    is_nullable = 'YES' AS nullable
FROM
    information_schema.columns
WHERE
    table_schema = #{schema}
    AND table_name = #{table}
ORDER BY
    ordinal_position
