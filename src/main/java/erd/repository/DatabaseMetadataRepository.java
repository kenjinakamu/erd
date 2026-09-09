package erd.repository;

import java.util.List;
import java.util.Set;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import erd.mybatis.externalsql.ExternalSqlProvider;
import erd.repository.model.RawColumn;
import erd.repository.model.UniqueConstraintColumn;

@Mapper
public interface DatabaseMetadataRepository {

	/**
	 * 現在のスキーマを取得
	 */
	@SelectProvider(ExternalSqlProvider.class)
	String currentSchema();

	/**
	 * カラムをリストで取得
	 */
	@SelectProvider(ExternalSqlProvider.class)
	List<RawColumn> selectRawColumnList(@Param("schema") String schema, @Param("table") String table);

	/**
	 * 主キーのカラムをセットで取得
	 */
	@SelectProvider(ExternalSqlProvider.class)
	Set<String> selectPkColumnSet(@Param("schema") String schema, @Param("table") String table);

	/**
	 * ユニーク制約のカラムを取得
	 */
	@SelectProvider(ExternalSqlProvider.class)
	List<UniqueConstraintColumn> selectUniqueColumnList(@Param("schema") String schema, @Param("table") String table);
}
