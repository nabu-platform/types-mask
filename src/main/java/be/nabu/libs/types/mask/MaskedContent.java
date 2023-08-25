package be.nabu.libs.types.mask;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import be.nabu.libs.types.BaseTypeInstance;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.ParsedPath;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeConverterFactory;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.SimpleType;
import be.nabu.libs.types.java.BeanType;

// currently we focus on having all edits take place in the new instance, so nothing is "by reference" edited
// we could make this toggleable so you can still edit the common fields in the original instance?
public class MaskedContent implements ComplexContent {

	private ComplexContent original, newInstance;
	private ComplexType targetType;
	private boolean allowUndefinedAccess = true;
	private Set<String> elementsSet = new HashSet<String>();

	public MaskedContent(ComplexContent original, ComplexType targetType) {
		this.original = original;
		this.targetType = targetType;
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void maskComplexChildren(ComplexContent original, ComplexType targetType) {
		newInstance = targetType.newInstance();
		// for all complex children: check if we need to mask the children! they need to be auto converted etc as well
		for (Element<?> child : TypeUtils.getAllChildren(original.getType())) {
			if (child.getType() instanceof ComplexType) {
				// check if the child exists in the target
				Element<?> element = targetType.get(child.getName());
				// always mask the values, even if they are the same type
				// for masked content => you don't want to set values in the original object (basically break the "by reference" explicitly)
				if (element != null) {
					// if the target is a java.lang.Object, don't mask it...
					if (element.getType() instanceof BeanType && Object.class.equals(((BeanType) element.getType()).getBeanClass())) {
						continue;
					}
					Object value = original.get(child.getName());
					if (value != null) {
						// @2023-06-28 maps are special, they do have collection handlers but we don't want to use that in this case if we are not mapping to a list at least
						// in the future we may want to skip the target list search, it is likely better to have a structured object in index 0 than the map exposed as multiple entries
						if (value instanceof Map && !element.getType().isList(element.getProperties())) {
							value = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(value);
						}
						CollectionHandlerProvider collectionHandler = CollectionHandlerFactory.getInstance().getHandler().getHandler(value.getClass());
						if (collectionHandler != null) {
							Collection indexes = collectionHandler.getIndexes(value);
							Object maskedCollection = collectionHandler.create(value.getClass(), indexes.size());
							for (Object index : indexes) {
								Object single = collectionHandler.get(value, index);
								if (single != null) {
									if (!(single instanceof ComplexContent)) {
										single = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(single);
									}
									single = new MaskedContent((ComplexContent) single, (ComplexType) element.getType());
								}
								collectionHandler.set(maskedCollection, index, single);
							}
							newInstance.set(child.getName(), maskedCollection);
						}
						else {
							Object converted = value;
							if (!(converted instanceof ComplexContent)) {
								converted = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(converted);
							}
							converted = new MaskedContent((ComplexContent) converted, (ComplexType) element.getType());
							newInstance.set(child.getName(), converted);
						}
					}
					// always add it to elements set, even if null
					// we don't want to accidently do null first, then create something in the original reference and have it (unmasked) in this instance
					elementsSet.add(child.getName());
				}
			}
		}
	}
	
	@Override
	public ComplexType getType() {
		return targetType;
	}

	private synchronized void initialize() {
		if (newInstance == null) {
			// only mask children on first SET, if we are not updating anything, we can use the original
			// once you update it however...you are in for some overhead
			maskComplexChildren(original, targetType);
		}
	}
	
	@Override
	public void set(String path, Object value) {
		if (newInstance == null) {
			initialize();
		}
		// we actively updated this field, mark it so don't request it from the original anymore
		elementsSet.add(new ParsedPath(path).getName());
		newInstance.set(path, value);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Object get(String path) {
		ParsedPath parsedPath = new ParsedPath(path);
		
		
		Element<?> element = targetType.get(parsedPath.getName());

		// in case you are getting a child to perform edits on later, we need to make sure you get a persistent reference
		if (element != null && element.getType() instanceof ComplexType && newInstance == null) {
			initialize();
		}
		
		// if we have an element that is not complex but we do want to recurse, throw exception
		if (element != null && !(element.getType() instanceof ComplexType) && parsedPath.getChildPath() != null) {
			throw new IllegalArgumentException("Can not get child path '" + parsedPath.getChildPath() + "' of non complex type: " + element.getName());
		}
		Object value = null;
		if (element != null || allowUndefinedAccess) {
			if (newInstance != null) {
				value = newInstance.get(parsedPath.getName());
			}
			// for simple values, we can still get the original
			if (value == null && !elementsSet.contains(parsedPath.getName())) {
				// only ask the original if it has that field
				// for example the bean instance will throw a hard error when requesting non-existing fields
				if (original.getType().get(parsedPath.getName()) != null) {
					value = original.get(parsedPath.getName());
				}
			}
		}
		
		if (value != null) {
			CollectionHandlerProvider collectionHandler = CollectionHandlerFactory.getInstance().getHandler().getHandler(value.getClass());
			
			// @2023-06-29: if we have a collection handler, but we don't have a target list, we want the first element (if any)
			if (collectionHandler != null && parsedPath.getIndex() == null && element != null && !element.getType().isList(element.getProperties())) {
				Iterable asIterable = collectionHandler.getAsIterable(value);
				Iterator iterator = asIterable.iterator();
				value = iterator.hasNext() ? iterator.next() : null;
				if (value == null) {
					return value;
				}
				// we have "resolved" the collection, we still want to allow for child access though
				else {
					collectionHandler = null;
				}
			}
			
			
			if (parsedPath.getIndex() != null) {
				if (collectionHandler == null) {
					throw new IllegalArgumentException("Can not find collection handler for: " + value.getClass());
				}
				value = collectionHandler.get(value, collectionHandler.unmarshalIndex(parsedPath.getIndex()));
			}
			
			
			// if we want a child value, get that
			if (value != null && parsedPath.getChildPath() != null) {
				if (collectionHandler != null && parsedPath.getIndex() == null) {
					throw new IllegalArgumentException("Can not get child of collection without providing an index");
				}
				// if it is a complex type, it should have been correctly cast to a complex content by the initialize routine
				if (!(value instanceof ComplexContent)) {
					throw new IllegalArgumentException("Not a complex type: " + parsedPath.getName());
				}
				// TODO: this does not allow you undefined access to the original additional children!
				// this is a change with before and might break stuff? though not sure what?
				value = ((ComplexContent) value).get(parsedPath.getChildPath().toString());
			}
			// if we have a collection of simple types, we need to cast them
			// note that a collection of complex types is already cast by the initialize
			else if (collectionHandler != null && parsedPath.getIndex() == null && element.getType() instanceof SimpleType) {
				Collection indexes = collectionHandler.getIndexes(value);
				Object newCollection = collectionHandler.create(value.getClass(), indexes.size());
				for (Object index : indexes) {
					newCollection = collectionHandler.set(newCollection, index, convert(element, collectionHandler.get(value, index)));
				}
				value = newCollection;
			}
			// again: non-simple types are cast by the initialize
			else if (element != null && element.getType() instanceof SimpleType) {
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
			if (((ComplexContent) value).getType().equals(element.getType()) || !TypeUtils.getUpcastPath(((ComplexContent) value).getType(), element.getType()).isEmpty()) {
				return value;
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

	public ComplexContent getOriginal() {
		return original;
	}
	
}
