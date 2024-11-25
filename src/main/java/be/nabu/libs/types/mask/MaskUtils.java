package be.nabu.libs.types.mask;

import java.util.ArrayList;
import java.util.List;

import be.nabu.libs.property.api.Value;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.Type;

public class MaskUtils {
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static Object mask(Object object, Type type, Value<?>...values) {
		if (object == null) {
			return null;
		}
		// if it is iterable, make sure we cast every instance of it
		if (object instanceof Iterable) {
			List list = new ArrayList();
			for (Object single : ((Iterable) object)) {
				list.add(mask(single, type));
			}
			return list;
		}
		else {
			// if it is a simple type, we just return the original, no masking involved
			DefinedSimpleType<? extends Object> simpleType = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(object.getClass());
			if (simpleType != null) {
				// TODO: could add simple type conversion?
				return object;
			}
			ComplexContent wrapped;
			if (!(object instanceof ComplexContent)) {
				wrapped = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(object);
				if (wrapped == null) {
					throw new IllegalArgumentException("Could not wrap object as complex content: " + object);
				}
			}
			else {
				wrapped = (ComplexContent) object;
			}
			// no casting necessary!
			if (TypeUtils.isExtension(wrapped.getType(), type)) {
				return wrapped;
			}
			else {
				return new MaskedContent(wrapped, (ComplexType) type);
			}
		}
	}
}
