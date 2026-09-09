package erd.mybatis.externalsql;

import java.lang.reflect.Method;

final class ExternalSqlPath {

	private ExternalSqlPath() {
	}

	static String of(Class<?> mapperType, Method mapperMethod) {
		String packagePath = mapperType.getPackageName().replace('.', '/');
		return "sql/" + packagePath + "/" + mapperType.getSimpleName() + "/" + mapperMethod.getName() + ".sql";
	}
}
