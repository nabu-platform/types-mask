package be.nabu.libs.types.mask;

import java.util.Collection;

import be.nabu.libs.types.BaseTypeInstance;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.ParsedPath;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeConverterFactory;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.SimpleType;

public class MaskedContent implements ComplexContent {

	private ComplexContent original;
	private ComplexType targetType;
	private boolean allowUndefinedAccess = true;

	public MaskedContent(ComplexContent original, ComplexType targetType) {
		this.original = original;
		this.targetType = targetType;
	}
	
	@Override
	public ComplexType getType() {
		return targetType;
	}

	@Override
	public void set(String path, Object value) {
		original.set(path, value);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Object get(String path) {
		ParsedPath parsedPath = new ParsedPath(path);
		Element<?> element = targetType.get(parsedPath.getName());
		// if we have an element that is not complex but we do want to recurse, throw exception
		if (element != null && !(element.getType() instanceof ComplexType) && parsedPath.getChildPath() != null) {
			throw new IllegalArgumentException("Can not get child path of non complex type: " + element.getName());
		}
		Object value = null;
		if (element != null || allowUndefinedAccess) {
			value = original.get(parsedPath.getName());
		}
		if (value != null) {
			CollectionHandlerProvider collectionHandler = CollectionHandlerFactory.getInstance().getHandler().getHandler(value.getClass());
			if (parsedPath.getIndex() != null) {
				if (collectionHandler == null) {
					throw new IllegalArgumentException("Can not find collection handler for: " + value.getClass());
				}
				value = collectionHandler.get(value, collectionHandler.unmarshalIndex(parsedPath.getIndex()));
			}
			
			if (value != null && parsedPath.getChildPath() != null) {
				if (collectionHandler != null) {
					throw new IllegalArgumentException("Can not get child of collection without providing an index");
				}
				if (!(value instanceof ComplexContent)) {
					value = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(value);
				}
				value = element == null
					? ((ComplexContent) value).get(parsedPath.getChildPath().toString())
					: new MaskedContent((ComplexContent) value, (ComplexType) element.getType()).get(parsedPath.getChildPath().toString());
			}
			else if (collectionHandler != null) {
				Collection indexes = collectionHandler.getIndexes(value);
				Object newCollection = collectionHandler.create(value.getClass(), indexes.size());
				for (Object index : indexes) {
					newCollection = collectionHandler.set(newCollection, index, convert(element, collectionHandler.get(value, index)));
				}
				value = newCollection;
			}
			else {
				value = convert(element, value);
			}
		}
		return value;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Object convert(Element<?> element, Object value) {
		if (value != null && element != null && element.getType() instanceof ComplexType) {
			if (!(value instanceof ComplexContent)) {
				value = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(value);
			}
			value = new MaskedContent((ComplexContent) value, (ComplexType) element.getType());
		}
		// do a simple type conversion
		else if (value != null && element != null) {
			SimpleType targetType = (SimpleType) element.getType();
			// convert if necessary
			if (!targetType.getInstanceClass().isAssignableFrom(value.getClass())) {
				// get the simple type of the original object
				DefinedSimpleType<? extends Object> wrap = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(value.getClass());
				if (wrap == null) {
					throw new IllegalArgumentException("Can not find simple type of " + value.getClass());
				}
				value = TypeConverterFactory.getInstance().getConverter().convert(value, new BaseTypeInstance(wrap), element);
				if (value == null) {
					throw new ClassCastException("Can not convert " + wrap + " to " + element.getType());
				}
			}
		}
		return value;
	}
	
	@Override
	public String toString() {
		return "Masking " + original + " as " + targetType;
	}
}
