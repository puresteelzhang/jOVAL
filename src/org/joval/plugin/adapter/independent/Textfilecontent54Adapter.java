// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.plugin.adapter.independent;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.bind.JAXBElement;

import oval.schemas.common.MessageLevelEnumeration;
import oval.schemas.common.MessageType;
import oval.schemas.common.SimpleDatatypeEnumeration;
import oval.schemas.definitions.core.ObjectType;
import oval.schemas.definitions.core.StateType;
import oval.schemas.definitions.independent.Textfilecontent54Object;
import oval.schemas.definitions.independent.Textfilecontent54State;
import oval.schemas.definitions.independent.Textfilecontent54Test;
import oval.schemas.systemcharacteristics.core.EntityItemAnySimpleType;
import oval.schemas.systemcharacteristics.core.EntityItemIntType;
import oval.schemas.systemcharacteristics.core.EntityItemStringType;
import oval.schemas.systemcharacteristics.core.ItemType;
import oval.schemas.systemcharacteristics.core.VariableValueType;
import oval.schemas.systemcharacteristics.independent.TextfilecontentItem;
import oval.schemas.results.core.ResultEnumeration;

import org.joval.intf.io.IFile;
import org.joval.intf.io.IFilesystem;
import org.joval.intf.plugin.IAdapter;
import org.joval.intf.plugin.IAdapterContext;
import org.joval.oval.OvalException;
import org.joval.oval.TestException;
import org.joval.util.BaseFileAdapter;
import org.joval.util.JOVALSystem;
import org.joval.util.StringTools;

/**
 * Evaluates Textfilecontent54Test OVAL tests.
 *
 * DAS: Specify a maximum file size supported
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class Textfilecontent54Adapter extends BaseFileAdapter {
    public Textfilecontent54Adapter(IFilesystem fs) {
	super(fs);
    }

    // Implement IAdapter

    public Class getObjectClass() {
	return Textfilecontent54Object.class;
    }

    public Class getStateClass() {
	return Textfilecontent54State.class;
    }

    public Class getItemClass() {
	return TextfilecontentItem.class;
    }

    public ResultEnumeration compare(StateType st, ItemType it) throws TestException, OvalException {
	Textfilecontent54State state = (Textfilecontent54State)st;
	TextfilecontentItem item = (TextfilecontentItem)it;

	if (state.isSetInstance()) {
	    return ctx.test(state.getInstance(), item.getInstance());
	}
	if (state.isSetText()) {
	    return ctx.test(state.getText(), item.getText());
	}
	if (state.isSetSubexpression()) {
	    List<EntityItemAnySimpleType> subexpressions = item.getSubexpression();
	    for (EntityItemAnySimpleType itemSubexpression : subexpressions) {
		ResultEnumeration result = ctx.test(state.getSubexpression(), itemSubexpression);
		switch (result) {
		  case FALSE:
		    // Check all the rest...
		    break;

		  default:
		    return result;
		}
	    }
	    return ResultEnumeration.FALSE;
	}

	return ResultEnumeration.TRUE;
    }

    // Protected

    protected Object convertFilename(EntityItemStringType filename) {
	return filename;
    }

    protected ItemType createFileItem() {
	return JOVALSystem.factories.sc.independent.createTextfilecontentItem();
    }

    /**
     * Parse the file as specified by the Object, and decorate the Item.
     */
    protected List<JAXBElement<? extends ItemType>>
	getItems(ItemType base, ObjectType obj, IFile f, List<VariableValueType> vars) throws IOException, OvalException {

	List<JAXBElement<? extends ItemType>> items = new Vector<JAXBElement<? extends ItemType>>();

	TextfilecontentItem baseItem = null;
	if (base instanceof TextfilecontentItem) {
	    baseItem = (TextfilecontentItem)base;
	}
	Textfilecontent54Object tfcObj = null;
	if (obj instanceof Textfilecontent54Object) {
	    tfcObj = (Textfilecontent54Object)obj;
	}

	if (baseItem != null && tfcObj != null) {
	    InputStream in = null;
	    try {
		int flags = 0;
		if (tfcObj.isSetBehaviors()) {
		    if (tfcObj.getBehaviors().isMultiline()) {
			flags |= Pattern.MULTILINE;
		    }
		    if (tfcObj.getBehaviors().isIgnoreCase()) {
			flags |= Pattern.CASE_INSENSITIVE;
		    }
		    if (tfcObj.getBehaviors().isSingleline()) {
			flags |= Pattern.DOTALL;
		    }
		} else {
		    flags = Pattern.MULTILINE;
		}
		List<Pattern> patterns = new Vector<Pattern>();
		if (tfcObj.getPattern().isSetVarRef()) {
		    for (String value : ctx.resolve(tfcObj.getPattern().getVarRef(), vars)) {
			patterns.add(Pattern.compile(value, flags));
		    }
		} else {
		    patterns.add(Pattern.compile((String)tfcObj.getPattern().getValue(), flags));
		}

		//
		// Read the whole file into a buffer and search for the pattern
		//
		byte[] buff = new byte[256];
		int len = 0;
		StringBuffer sb = new StringBuffer();
		in = f.getInputStream();
		while ((len = in.read(buff)) > 0) {
		    sb.append(StringTools.toCharArray(buff), 0, len);
		}
		String s = sb.toString();

		for (Pattern p : patterns) {
		    Matcher m = p.matcher(s);
		    for (int instanceNum=1; m.find(); instanceNum++) {
			TextfilecontentItem item = JOVALSystem.factories.sc.independent.createTextfilecontentItem();
			item.setPath(baseItem.getPath());
			item.setFilename(baseItem.getFilename());
    
			EntityItemStringType patternType = JOVALSystem.factories.sc.core.createEntityItemStringType();
			patternType.setValue(p.toString());
			item.setPattern(patternType);
			EntityItemIntType instanceType = JOVALSystem.factories.sc.core.createEntityItemIntType();
			instanceType.setDatatype(SimpleDatatypeEnumeration.INT.value());
			instanceType.setValue(Integer.toString(instanceNum));
			item.setInstance(instanceType);
    
			EntityItemAnySimpleType textType = JOVALSystem.factories.sc.core.createEntityItemAnySimpleType();
			textType.setValue(m.group());
			item.setText(textType);
			int sCount = m.groupCount();
			if (sCount > 0) {
			    for (int sNum=1; sNum <= sCount; sNum++) {
				EntityItemAnySimpleType sType = JOVALSystem.factories.sc.core.createEntityItemAnySimpleType();
				sType.setValue(m.group(sNum));
				item.getSubexpression().add(sType);
			    }
			}
    
			items.add(JOVALSystem.factories.sc.independent.createTextfilecontentItem(item));
		    }
		}
	    } catch (PatternSyntaxException e) {
		throw new IOException(e);
	    } finally {
		if (in != null) {
		    try {
			in.close();
		    } catch (IOException e) {
			ctx.log(Level.WARNING, JOVALSystem.getMessage("ERROR_FILE_STREAM_CLOSE", f.toString()), e);
		    }
		}
	    }
	}
	return items;
    }
}
