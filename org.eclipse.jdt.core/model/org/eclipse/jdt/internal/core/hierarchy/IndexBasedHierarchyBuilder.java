package org.eclipse.jdt.internal.core.hierarchy;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
import org.eclipse.core.runtime.*;

import org.eclipse.core.resources.*;

import org.eclipse.jdt.core.*;
import org.eclipse.core.resources.*;

import org.eclipse.jdt.core.search.*;

import java.util.*;

import org.eclipse.jdt.internal.core.search.matching.SuperTypeReferencePattern;
import org.eclipse.jdt.internal.core.search.indexing.IndexManager;
import org.eclipse.jdt.internal.core.search.matching.SearchPattern;
import org.eclipse.jdt.internal.core.search.*;
import org.eclipse.jdt.internal.compiler.util.CharOperation;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.IGenericType;
import org.eclipse.jdt.internal.core.search.indexing.AbstractIndexer;
import org.eclipse.jdt.internal.core.*;
import org.eclipse.jdt.internal.core.Util;
import org.eclipse.jdt.internal.compiler.util.HashtableOfObject;

import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

public class IndexBasedHierarchyBuilder extends HierarchyBuilder {
	/**
	 * A temporary cache of compilation units to handles to speed info
	 * to handle translation - it only contains the entries
	 * for the types in the region (i.e. no supertypes outside
	 * the region).
	 */
	protected Map cuToHandle;
	/**
	 * A map from compilation unit handles to working copies.
	 */
	protected Map handleToWorkingCopy;

	/**
	 * The scope this hierarchy builder should restrain results to.
	 */
	protected IJavaSearchScope scope;

	/**
	 * Cache used to record binaries recreated from index matches
	 */
	protected Map binariesFromIndexMatches;
	
	/**
	 * Collection used to queue subtype index queries
	 */
	private static class Queue {
		public char[][] names = new char[10][];
		public int start = 0;
		public int end = -1;
		public void add(char[] name){
			if (++this.end == this.names.length){
				this.end -= this.start;
				System.arraycopy(this.names, this.start, this.names = new char[this.end*2][], 0, this.end);
				this.start = 0;
			}
			this.names[this.end] = name;
		}
		public char[] retrieve(){
			if (this.start > this.end) return null; // none
			
			char[] name = this.names[this.start++];
			if (this.start > this.end){
				this.start = 0;
				this.end = -1;
			}
			return name;
		}
		public String toString(){
			StringBuffer buffer = new StringBuffer("Queue:\n"); //$NON-NLS-1$
			for (int i = this.start; i <= this.end; i++){
				buffer.append(names[i]).append('\n');		
			}
			return buffer.toString();
		}
	}
public IndexBasedHierarchyBuilder(TypeHierarchy hierarchy, IJavaSearchScope scope) throws JavaModelException {
	super(hierarchy);
	this.cuToHandle = new HashMap(5);
	this.binariesFromIndexMatches = new HashMap(10);
	this.scope = scope;
}
/**
 * Add the type info from the given hierarchy binary type to the given list of infos.
 */
private void addInfoFromBinaryIndexMatch(Openable handle, HierarchyBinaryType binaryType, ArrayList infos) throws JavaModelException {
	infos.add(binaryType);
	this.infoToHandle.put(binaryType, handle);
}
/**
 * Add the type info from the given class file to the given list of infos.
 */
private void addInfoFromOpenClassFile(ClassFile classFile, ArrayList infos) throws JavaModelException {
	IType type = classFile.getType();
	IGenericType info = (IGenericType) ((BinaryType) type).getRawInfo();
	infos.add(info);
	this.infoToHandle.put(info, classFile);
}
/**
 * Add the type info from the given CU to the given list of infos.
 */
private void addInfoFromOpenCU(CompilationUnit cu, ArrayList infos) throws JavaModelException {
	IType[] types = cu.getTypes();
	for (int j = 0; j < types.length; j++) {
		SourceType type = (SourceType)types[j];
		this.addInfoFromOpenSourceType(type, infos);
	}
}
/**
 * Add the type info from the given CU to the given list of infos.
 */
private void addInfoFromOpenSourceType(SourceType type, ArrayList infos) throws JavaModelException {
	IGenericType info = (IGenericType)type.getRawInfo();
	infos.add(info);
	this.infoToHandle.put(info, type);
	IType[] members = type.getTypes();
	for (int i = 0; i < members.length; i++) {
		this.addInfoFromOpenSourceType((SourceType)members[i], infos);
	}
}
/**
 * Add the type info (and its sibblings type infos) to the given list of infos.
 */
private void addInfosFromType(IType type, ArrayList infos) throws JavaModelException {
	if (type.isBinary()) {
		// add class file
		ClassFile classFile = (ClassFile)type.getClassFile();
		if (classFile != null) {
			this.addInfoFromOpenClassFile(classFile, infos);
		}
	} else {
		// add whole cu (if it is a working copy, it's types can be potential subtypes)
		CompilationUnit unit = (CompilationUnit)type.getCompilationUnit();
		if (unit != null) {
			this.addInfoFromOpenCU(unit, infos);
		}
	}
}
public void build(boolean computeSubtypes) throws JavaModelException, CoreException {
	if (computeSubtypes) {
		String[] allPossibleSubtypes = this.determinePossibleSubTypes();
		if (allPossibleSubtypes != null) {
			this.hierarchy.initialize(allPossibleSubtypes.length);
			buildFromPotentialSubtypes(allPossibleSubtypes);
		}
	} else {
		this.hierarchy.initialize(1);
		this.buildSupertypes();
	}
}
private void buildForProject(JavaProject project, ArrayList infos, ArrayList units) throws JavaModelException {
	// copy vectors into arrays
	IGenericType[] genericTypes;
	int infosSize = infos.size();
	if (infosSize > 0) {
		genericTypes = new IGenericType[infosSize];
		infos.toArray(genericTypes);
	} else {
		genericTypes = new IGenericType[0];
	}
	ICompilationUnit[] compilationUnits;
	int unitsSize = units.size();
	if (unitsSize > 0) {
		compilationUnits = new ICompilationUnit[unitsSize];
		units.toArray(compilationUnits);
	} else {
		compilationUnits = new ICompilationUnit[0];
	}

	// resolve
	if (infosSize > 0 || unitsSize > 0) {
		this.searchableEnvironment = (SearchableEnvironment)project.getSearchableNameEnvironment();
		IType focusType = this.getType();
		this.nameLookup = project.getNameLookup();
		boolean inProjectOfFocusType = focusType != null && focusType.getJavaProject().equals(project);
		if (inProjectOfFocusType) {
			org.eclipse.jdt.core.ICompilationUnit unitToLookInside = focusType.getCompilationUnit();
			if (unitToLookInside != null) {
				this.nameLookup.setUnitsToLookInside(new IWorkingCopy[] {unitToLookInside});
			}
		}
		try {
			this.hierarchyResolver = 
				new HierarchyResolver(this.searchableEnvironment, JavaCore.getOptions(), this, new DefaultProblemFactory());
			if (focusType != null) {
				char[] fullyQualifiedName = focusType.getFullyQualifiedName().toCharArray();
				ReferenceBinding focusTypeBinding = this.hierarchyResolver.setFocusType(CharOperation.splitOn('.', fullyQualifiedName));
				if (focusTypeBinding == null 
					|| (!inProjectOfFocusType && (focusTypeBinding.tagBits & TagBits.HierarchyHasProblems) > 0)) {
					// focus type is not visible in this project: no need to go further
					return;
				}
			}
			this.hierarchyResolver.resolve(genericTypes, compilationUnits);
		} finally {
			if (inProjectOfFocusType) {
				this.nameLookup.setUnitsToLookInside(null);
			}
		}
	}
}
/**
 * Configure this type hierarchy based on the given potential subtypes.
 */
private void buildFromPotentialSubtypes(String[] allPotentialSubTypes) {
	IType focusType = this.getType();
		
	int length = allPotentialSubTypes.length;

	// inject the compilation unit of the focus type (so that types in
	// this cu have special visibility permission (this is also usefull
	// when the cu is a working copy)
	Openable focusCU = (Openable)focusType.getCompilationUnit();
	String focusPath = null;
	if (focusCU != null) {
		try {
			IResource underlyingResource;
			if (focusCU instanceof WorkingCopy) {
				underlyingResource = ((WorkingCopy)focusCU).getOriginalElement().getUnderlyingResource();
			} else {
				underlyingResource = focusCU.getUnderlyingResource();
			}
			focusPath = underlyingResource.getFullPath().toString();
		} catch (JavaModelException e) {
			// type does not exist
			return;
		}
		if (length > 0) {
			System.arraycopy(allPotentialSubTypes, 0, allPotentialSubTypes = new String[length+1], 0, length);
			allPotentialSubTypes[length] = focusPath;	
		} else {
			allPotentialSubTypes = new String[] {focusPath};
		}
		length++;
	}
	
	// sort by projects
	/*
	 * NOTE: To workaround pb with hierarchy resolver that requests top  
	 * level types in the process of caching an enclosing type, this needs to
	 * be sorted in reverse alphabetical order so that top level types are cached
	 * before their inner types.
	 */
	Util.sortReverseOrder(allPotentialSubTypes);
	
	ArrayList infos = new ArrayList();
	ArrayList units = new ArrayList();

	// create element infos for subtypes
	IWorkspace workspace = focusType.getJavaProject().getProject().getWorkspace();
	HandleFactory factory = new HandleFactory(workspace);
	IJavaProject currentProject = null;
	for (int i = 0; i < length; i++) {
		try {
			String resourcePath = allPotentialSubTypes[i];
			
			// skip duplicate paths (e.g. if focus path was injected when it was already a potential subtype)
			if (i > 0 && resourcePath.equals(allPotentialSubTypes[i-1])) continue;
			
			Openable handle = 
				resourcePath.equals(focusPath) ? 
					focusCU :
					factory.createOpenable(resourcePath);
			if (handle == null) continue; // match is outside classpath
			
			IJavaProject project = handle.getJavaProject();
			if (currentProject == null) {
				currentProject = project;
				infos = new ArrayList(5);
				units = new ArrayList(5);
			} else if (!currentProject.equals(project)) {
				// build current project
				this.buildForProject((JavaProject)currentProject, infos, units);
				currentProject = project;
				infos = new ArrayList(5);
				units = new ArrayList(5);
			}
			if (handle.isOpen()) {
				// reuse the info from the java model cache
				if (handle instanceof CompilationUnit) {
					this.addInfoFromOpenCU((CompilationUnit)handle, infos);
				} else if (handle instanceof ClassFile) {
					this.addInfoFromOpenClassFile((ClassFile)handle, infos);
				}
			} else {
				HierarchyBinaryType binaryType = (HierarchyBinaryType) binariesFromIndexMatches.get(resourcePath);
				if (binaryType != null){
					this.addInfoFromBinaryIndexMatch(handle, binaryType, infos);
				} else {
					// create a temporary info
					IJavaElement pkg = handle.getParent();
					PackageFragmentRoot root = (PackageFragmentRoot)pkg.getParent();
					if (root.isArchive()) {
						// class file in a jar
						this.createInfoFromClassFileInJar(handle, infos);
					} else {
						// file in a directory
						IPath path = new Path(resourcePath);
						IFile file = workspace.getRoot().getFile(path);
						IPath location = file.getLocation();
						if (location != null){
							String osPath = location.toOSString();
							if (handle instanceof CompilationUnit) {
								// compilation unit in a directory
								this.createCompilationUnitFromPath(handle, osPath, units);
							} else if (handle instanceof ClassFile) {
								// class file in a directory
								this.createInfoFromClassFile(handle, osPath, infos);
							}
						}
					}
				}
			}
			worked(1);
		} catch (JavaModelException e) {
			continue;
		}
	}
	
	// build last project
	try {
		if (currentProject == null) {
			// case of no potential subtypes
			currentProject = focusType.getJavaProject();
			this.addInfosFromType(focusType, infos);
		}
		this.buildForProject((JavaProject)currentProject, infos, units);
	} catch (JavaModelException e) {
	}
	
	// Compute hierarchy of focus type if not already done (case of a type with potential subtypes that are not real subtypes)
	if (!this.hierarchy.contains(focusType)) {
		try {
			currentProject = focusType.getJavaProject();
			infos = new ArrayList();
			units = new ArrayList();
			this.addInfosFromType(focusType, infos);
			this.buildForProject((JavaProject)currentProject, infos, units);
		} catch (JavaModelException e) {
		}
	}
	
	// Add focus if not already in (case of a type with no explicit super type)
	if (!this.hierarchy.contains(focusType)) {
		this.hierarchy.addRootClass(focusType);
	}
}
/**
 * Create an ICompilationUnit info from the given compilation unit on disk and 
 * adds it to the given list of units.
 */
private void createCompilationUnitFromPath(Openable handle, String osPath, ArrayList units) throws JavaModelException {
	String encoding = (String) JavaCore.getOptions().get(CompilerOptions.OPTION_Encoding);
	if ("".equals(encoding)) encoding = null; //$NON-NLS-1$
	BasicCompilationUnit unit = 
		new BasicCompilationUnit(
			null,
			osPath,
			encoding);
	units.add(unit);
	this.cuToHandle.put(unit, handle);
}
/**
 * Creates the type info from the given class file on disk and
 * adds it to the given list of infos.
 */
private void createInfoFromClassFile(Openable handle, String osPath, ArrayList infos) throws JavaModelException {
	IGenericType info = null;
	try {
		info = org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader.read(osPath);
	} catch (org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException e) {
		e.printStackTrace();
		return;
	} catch (java.io.IOException e) {
		e.printStackTrace();
		return;
	}						
	infos.add(info);
	this.infoToHandle.put(info, handle);
}
/**
 * Create a type info from the given class file in a jar and adds it to the given list of infos.
 */
private void createInfoFromClassFileInJar(Openable classFile, ArrayList infos) throws JavaModelException {
	IJavaElement pkg = classFile.getParent();
	String classFilePath = pkg.getElementName().replace('.', '/') + "/" + classFile.getElementName(); //$NON-NLS-1$
	IGenericType info = null;
	java.util.zip.ZipFile zipFile = null;
	try {
		zipFile = ((JarPackageFragmentRoot)pkg.getParent()).getJar();
		info = org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader.read(
			zipFile,
			classFilePath);
	} catch (org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException e) {
		e.printStackTrace();
		return;
	} catch (java.io.IOException e) {
		e.printStackTrace();
		return;
	} catch (CoreException e) {
		e.printStackTrace();
		return;
	} finally {
		if (zipFile != null) {
			try {
				zipFile.close();
			} catch (java.io.IOException e) {
				// ignore 
			}
		}	
	}
	infos.add(info);
	this.infoToHandle.put(info, classFile);
}
/**
 * Returns all of the possible subtypes of this type hierarchy.
 * Returns null if they could not be determine.
 */
private String[] determinePossibleSubTypes() throws JavaModelException, CoreException {

	class PathCollector implements IPathRequestor {
		HashSet paths = new HashSet(10);
		public void acceptPath(String path) {
			paths.add(path);
		}
	}
	PathCollector collector = new PathCollector();
	IProject project = this.hierarchy.javaProject().getProject();
	
	searchAllPossibleSubTypes(
		project.getWorkspace(),
		this.getType(),
		this.scope,
		this.binariesFromIndexMatches,
		collector,
		IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
		this.hierarchy.progressMonitor);

	HashSet paths = collector.paths;
	int length = paths.size();
	String[] result = new String[length];
	int count = 0;
	for (Iterator iter = paths.iterator(); iter.hasNext();) {
		result[count++] = (String) iter.next();
	} 
	return result;
}
/**
 * Returns a handle for the given generic type or null if not found.
 */
protected IType getHandle(IGenericType genericType) {
	if (genericType instanceof HierarchyType) {
		IType type = (IType)this.infoToHandle.get(genericType);
		if (type == null) {
			HierarchyType hierarchyType = (HierarchyType)genericType;
			CompilationUnit unit = (CompilationUnit)this.cuToHandle.get(hierarchyType.originatingUnit);

			// collect enclosing type names
			ArrayList enclosingTypeNames = new ArrayList();
			HierarchyType enclosingType = hierarchyType;
			do {
				enclosingTypeNames.add(enclosingType.name);
				enclosingType = enclosingType.enclosingType;
			} while (enclosingType != null);
			int length = enclosingTypeNames.size();
			char[][] simpleTypeNames = new char[length][];
			enclosingTypeNames.toArray(simpleTypeNames);

			// build handle
			type = unit.getType(new String(simpleTypeNames[length-1]));
			for (int i = length-2; i >= 0; i--) {
				type = type.getType(new String(simpleTypeNames[i]));
			}
			this.infoToHandle.put(genericType, type);
		}
		return type;
	} else
		return super.getHandle(genericType);
}
/**
 * Find the set of candidate subtypes of a given type.
 *
 * The requestor is notified of super type references (with actual path of
 * its occurrence) for all types which are potentially involved inside a particular
 * hierarchy.
 * The match locator is not used here to narrow down the results, the type hierarchy
 * resolver is rather used to compute the whole hierarchy at once.
 */

public static void searchAllPossibleSubTypes(
	IWorkspace workbench,
	IType type,
	IJavaSearchScope scope,
	final Map binariesFromIndexMatches,
	final IPathRequestor pathRequestor,
	int waitingPolicy,	// WaitUntilReadyToSearch | ForceImmediateSearch | CancelIfNotReadyToSearch
	IProgressMonitor progressMonitor)  throws JavaModelException, CoreException {

	/* embed constructs inside arrays so as to pass them to (inner) collector */
	final Queue awaitings = new Queue();
	final HashtableOfObject foundSuperNames = new HashtableOfObject(5);

	IndexManager indexManager = ((JavaModelManager)JavaModelManager.getJavaModelManager()).getIndexManager();

	/* use a special collector to collect paths and queue new subtype names */
	IIndexSearchRequestor searchRequestor = new IndexSearchAdapter(){
		public void acceptSuperTypeReference(String resourcePath, char[] qualification, char[] typeName, char[] enclosingTypeName, char classOrInterface, char[] superQualification, char[] superTypeName, char superClassOrInterface, int modifiers) {
			pathRequestor.acceptPath(resourcePath);
			if (resourcePath.endsWith("class")){ //$NON-NLS-1$
				HierarchyBinaryType binaryType = (HierarchyBinaryType)binariesFromIndexMatches.get(resourcePath);
				if (binaryType == null){
					binaryType = new HierarchyBinaryType(modifiers, qualification, typeName, enclosingTypeName, classOrInterface);
					binariesFromIndexMatches.put(resourcePath, binaryType);
				}
				binaryType.recordSuperType(superTypeName, superQualification, superClassOrInterface);
			}
			if (!foundSuperNames.containsKey(typeName)){
				foundSuperNames.put(typeName, typeName);
				awaitings.add(typeName);
			}
		}		
	};
	
	SuperTypeReferencePattern pattern = new SuperTypeReferencePattern(null, null, IJavaSearchConstants.EXACT_MATCH, IJavaSearchConstants.CASE_SENSITIVE);
	SubTypeSearchJob job = new SubTypeSearchJob(
				pattern, 
				scope,
				type, 
				IInfoConstants.PathInfo, 
				searchRequestor, 
				indexManager);
	
	/* initialize entry result cache */
	pattern.entryResults = new HashMap();
	/* iterate all queued names */
	int ticks = 50;
	awaitings.add(type.getElementName().toCharArray());
	while (awaitings.start <= awaitings.end){
		if (progressMonitor != null && progressMonitor.isCanceled()) return;

		char[] currentTypeName = awaitings.retrieve();

		/* all subclasses of OBJECT are actually all types */
		if (CharOperation.equals(currentTypeName, AbstractIndexer.OBJECT)){
			currentTypeName = null;
		}			
		/* search all index references to a given supertype */
		pattern.superSimpleName = currentTypeName;
		indexManager.performConcurrentJob(
			job, 
			waitingPolicy, 
			progressMonitor == null ? null : new SubProgressMonitor(progressMonitor, ticks));
		/* in case, we search all subtypes, no need to search further */
		if (currentTypeName == null) break;
		ticks = ticks / 2;
	}
	/* close all cached index inputs */
	job.closeAll();
	/* flush entry result cache */
	pattern.entryResults = null;
}
}
