package erd.mybatis.externalsql;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

final class ExternalSqlMethodDetector {

	private ExternalSqlMethodDetector() {
	}

	static boolean isExternalSqlMethod(Method method) {
		return Arrays.stream(method.getAnnotationsByType(SelectProvider.class))
				.anyMatch(annotation -> isExternal(annotation.value(), annotation.type()))
				|| Arrays.stream(method.getAnnotationsByType(InsertProvider.class))
						.anyMatch(annotation -> isExternal(annotation.value(), annotation.type()))
				|| Arrays.stream(method.getAnnotationsByType(UpdateProvider.class))
						.anyMatch(annotation -> isExternal(annotation.value(), annotation.type()))
				|| Arrays.stream(method.getAnnotationsByType(DeleteProvider.class))
						.anyMatch(annotation -> isExternal(annotation.value(), annotation.type()));
	}

	private static boolean isExternal(Class<?> value, Class<?> type) {
		return value == ExternalSqlProvider.class || type == ExternalSqlProvider.class;
	}
}
