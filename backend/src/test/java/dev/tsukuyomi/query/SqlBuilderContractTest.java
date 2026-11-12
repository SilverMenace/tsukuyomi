package dev.tsukuyomi.query;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.tsukuyomi.query.ast.Filter;
import dev.tsukuyomi.query.ast.OrderTerm;
import dev.tsukuyomi.query.ast.Param;
import dev.tsukuyomi.query.ast.Relation;
import dev.tsukuyomi.query.ast.SelectItem;
import dev.tsukuyomi.query.ast.SelectQuery;
import dev.tsukuyomi.query.ast.WriteCommand;
import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.TableDef;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two structural rules that make the SQL builder injection-safe.
 * These tests fail if someone later adds a shortcut that breaks them, even if
 * every behavioural test still passes.
 */
class SqlBuilderContractTest {

	/** Rule 1: nothing outside the builder can hand it a piece of text. */
	@Test
	void noPublicMethodOfSqlBuilderAcceptsText() {
		for (Method method : SqlBuilder.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(method.getModifiers())) {
				continue;
			}
			for (Class<?> parameter : method.getParameterTypes()) {
				assertThat(parameter).as("parameter of SqlBuilder.%s", method.getName())
					.isNotIn(String.class, CharSequence.class, Object.class, StringBuilder.class);
			}
		}
	}

	/**
	 * Rule 4: in the whole AST, the only place a String can live is Param.
	 * Catalog types (TableDef, ColumnDef) hold names too, but those come from
	 * the catalog and are re-checked by Identifiers.quote when emitted.
	 */
	@Test
	void theOnlyAstTypeHoldingTextIsParam() {
		Set<Class<?>> catalogTypes = Set.of(TableDef.class, ColumnDef.class);
		Set<Class<?>> seen = new HashSet<>();
		Deque<Class<?>> todo = new ArrayDeque<>(List.of(SelectQuery.class, WriteCommand.Insert.class,
				WriteCommand.Update.class, WriteCommand.Delete.class, WriteCommand.Assignment.class, Filter.class,
				OrderTerm.class, Relation.class, SelectItem.Column.class, SelectItem.Embed.class));

		while (!todo.isEmpty()) {
			Class<?> type = todo.pop();
			if (!seen.add(type) || catalogTypes.contains(type) || type == Param.class || !type.isRecord()) {
				continue;
			}
			for (RecordComponent component : type.getRecordComponents()) {
				for (Class<?> used : classesIn(component.getGenericType())) {
					assertThat(used).as("%s.%s", type.getSimpleName(), component.getName())
						.isNotIn(String.class, CharSequence.class, Object.class);
					todo.push(used);
				}
			}
		}
		assertThat(seen).contains(Filter.class, SelectItem.Embed.class, Relation.class);
	}

	/** The classes mentioned by a type, looking inside generics such as List<List<Param>>. */
	private static Set<Class<?>> classesIn(Type type) {
		Set<Class<?>> classes = new HashSet<>();
		if (type instanceof Class<?> c) {
			if (c != List.class && !c.isPrimitive()) {
				classes.add(c);
			}
		}
		else if (type instanceof ParameterizedType p) {
			classes.addAll(classesIn(p.getRawType()));
			for (Type argument : p.getActualTypeArguments()) {
				classes.addAll(classesIn(argument));
			}
		}
		else {
			classes.add(Object.class); // wildcards or type variables: treat as "could be anything"
		}
		return classes;
	}

}
