package gr.uom.java.jdeodorant.refactoring.views;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import gr.uom.java.ast.ASTReader;
import gr.uom.java.ast.ClassObject;
import gr.uom.java.ast.CompilationUnitCache;
import gr.uom.java.ast.MethodObject;
import gr.uom.java.ast.SystemObject;
import gr.uom.java.ast.decomposition.cfg.CFG;
import gr.uom.java.ast.decomposition.cfg.PDG;
import gr.uom.java.ast.decomposition.cfg.PDGObjectSliceUnion;
import gr.uom.java.ast.decomposition.cfg.PDGObjectSliceUnionCollection;
import gr.uom.java.ast.decomposition.cfg.PDGSlice;
import gr.uom.java.ast.decomposition.cfg.PDGSliceUnion;
import gr.uom.java.ast.decomposition.cfg.PDGSliceUnionCollection;
import gr.uom.java.ast.decomposition.cfg.PlainVariable;
import gr.uom.java.jdeodorant.refactoring.manipulators.ASTSlice;
import gr.uom.java.jdeodorant.refactoring.manipulators.ExtractMethodRefactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableLayout;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.ui.refactoring.RefactoringWizardOpenOperation;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

public class LongMethod extends ViewPart {
	private TableViewer tableViewer;
	private Action identifyBadSmellsAction;
	private Action applyRefactoringAction;
	private Action doubleClickAction;
	private Action renameMethodAction;
	private Action saveResultsAction;
	private IJavaProject selectedProject;
	private IPackageFragment selectedPackage;
	private ICompilationUnit selectedCompilationUnit;
	private IType selectedType;
	private IMethod selectedMethod;
	private ASTSlice[] sliceTable;
	
	class ViewContentProvider implements IStructuredContentProvider {
		public void inputChanged(Viewer v, Object oldInput, Object newInput) {
		}
		public void dispose() {
		}
		public Object[] getElements(Object parent) {
			if(sliceTable!=null) {
				return sliceTable;
			}
			else {
				return new PDGSlice[] {};
			}
		}
	}

	class ViewLabelProvider extends LabelProvider implements ITableLabelProvider {
		public String getColumnText(Object obj, int index) {
			ASTSlice entry = (ASTSlice)obj;
			switch(index){
			case 0:
				return "Extract Method";
			case 1:
				String declaringClass = entry.getSourceTypeDeclaration().resolveBinding().getQualifiedName();
				String methodName = entry.getSourceMethodDeclaration().resolveBinding().toString();
				return declaringClass + "::" + methodName;
			case 2:
				return entry.getLocalVariableCriterion().toString();
			case 3:
				return "B" + entry.getBoundaryBlock().getId();
			case 4:
				int numberOfSliceStatements = entry.getSliceStatements().size();
				int numberOfRemovableStatements = entry.getRemovableStatements().size();
				int numberOfDuplicatedStatements = numberOfSliceStatements - numberOfRemovableStatements;
				return numberOfDuplicatedStatements + "/" + numberOfSliceStatements;
			default:
				return "";
			}
			
		}
		public Image getColumnImage(Object obj, int index) {
			return null;
		}
		public Image getImage(Object obj) {
			return null;
		}
	}

	class NameSorter extends ViewerSorter {
		public int compare(Viewer viewer, Object obj1, Object obj2) {
			ASTSlice slice1 = (ASTSlice)obj1;
			ASTSlice slice2 = (ASTSlice)obj2;
			
			int numberOfSliceStatements1 = slice1.getSliceStatements().size();
			int numberOfRemovableStatements1 = slice1.getRemovableStatements().size();
			int numberOfDuplicatedStatements1 = numberOfSliceStatements1 - numberOfRemovableStatements1;
			double ratio1 = (double)numberOfDuplicatedStatements1/(double)numberOfSliceStatements1;
			
			int numberOfSliceStatements2 = slice2.getSliceStatements().size();
			int numberOfRemovableStatements2 = slice2.getRemovableStatements().size();
			int numberOfDuplicatedStatements2 = numberOfSliceStatements2 - numberOfRemovableStatements2;
			double ratio2 = (double)numberOfDuplicatedStatements2/(double)numberOfSliceStatements2;
			
			if(ratio1 < ratio2) {
				return -1;
			}
			else if(ratio1 > ratio2) {
				return 1;
			}
			else {
				if(numberOfDuplicatedStatements1 == 0 && numberOfDuplicatedStatements2 == 0)
					return -Integer.valueOf(numberOfSliceStatements1).compareTo(Integer.valueOf(numberOfSliceStatements2));
				return 0;
			}
		}
	}
	
	private ISelectionListener selectionListener = new ISelectionListener() {
		public void selectionChanged(IWorkbenchPart sourcepart, ISelection selection) {
			if (selection instanceof IStructuredSelection) {
				IStructuredSelection structuredSelection = (IStructuredSelection)selection;
				Object element = structuredSelection.getFirstElement();
				IJavaProject javaProject = null;
				if(element instanceof IJavaProject) {
					javaProject = (IJavaProject)element;
					selectedPackage = null;
					selectedCompilationUnit = null;
					selectedType = null;
					selectedMethod = null;
				}
				else if(element instanceof IPackageFragmentRoot) {
					IPackageFragmentRoot packageFragmentRoot = (IPackageFragmentRoot)element;
					javaProject = packageFragmentRoot.getJavaProject();
					selectedPackage = null;
					selectedCompilationUnit = null;
					selectedType = null;
					selectedMethod = null;
				}
				else if(element instanceof IPackageFragment) {
					IPackageFragment packageFragment = (IPackageFragment)element;
					javaProject = packageFragment.getJavaProject();
					selectedPackage = packageFragment;
					selectedCompilationUnit = null;
					selectedType = null;
					selectedMethod = null;
				}
				else if(element instanceof ICompilationUnit) {
					ICompilationUnit compilationUnit = (ICompilationUnit)element;
					javaProject = compilationUnit.getJavaProject();
					selectedCompilationUnit = compilationUnit;
					selectedPackage = null;
					selectedType = null;
					selectedMethod = null;
				}
				else if(element instanceof IType) {
					IType type = (IType)element;
					javaProject = type.getJavaProject();
					selectedType = type;
					selectedPackage = null;
					selectedCompilationUnit = null;
					selectedMethod = null;
				}
				else if(element instanceof IMethod) {
					IMethod method = (IMethod)element;
					javaProject = method.getJavaProject();
					selectedMethod = method;
					selectedPackage = null;
					selectedCompilationUnit = null;
					selectedType = null;
				}
				if(javaProject != null && !javaProject.equals(selectedProject)) {
					selectedProject = javaProject;
					if(sliceTable != null)
						tableViewer.remove(sliceTable);
					identifyBadSmellsAction.setEnabled(true);
					applyRefactoringAction.setEnabled(false);
					renameMethodAction.setEnabled(false);
					saveResultsAction.setEnabled(false);
				}
			}
		}
	};

	@Override
	public void createPartControl(Composite parent) {
		tableViewer = new TableViewer(parent, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER | SWT.FULL_SELECTION);
		tableViewer.setContentProvider(new ViewContentProvider());
		tableViewer.setLabelProvider(new ViewLabelProvider());
		//tableViewer.setSorter(new NameSorter());
		tableViewer.setInput(getViewSite());
		TableLayout layout = new TableLayout();
		layout.addColumnData(new ColumnWeightData(20, true));
		layout.addColumnData(new ColumnWeightData(60, true));
		layout.addColumnData(new ColumnWeightData(40, true));
		layout.addColumnData(new ColumnWeightData(20, true));
		layout.addColumnData(new ColumnWeightData(20, true));
		tableViewer.getTable().setLayout(layout);
		tableViewer.getTable().setLinesVisible(true);
		tableViewer.getTable().setHeaderVisible(true);
		TableColumn column0 = new TableColumn(tableViewer.getTable(),SWT.LEFT);
		column0.setText("Refactoring Type");
		column0.setResizable(true);
		column0.pack();
		TableColumn column1 = new TableColumn(tableViewer.getTable(),SWT.LEFT);
		column1.setText("Source Method");
		column1.setResizable(true);
		column1.pack();
		TableColumn column2 = new TableColumn(tableViewer.getTable(),SWT.LEFT);
		column2.setText("Variable Criterion");
		column2.setResizable(true);
		column2.pack();
		TableColumn column3 = new TableColumn(tableViewer.getTable(),SWT.LEFT);
		column3.setText("Block-Based Region");
		column3.setResizable(true);
		column3.pack();
		TableColumn column4 = new TableColumn(tableViewer.getTable(),SWT.LEFT);
		column4.setText("Duplicated/Extracted");
		column4.setResizable(true);
		column4.pack();
		makeActions();
		hookDoubleClickAction();
		contributeToActionBars();
		getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(selectionListener);
		getSite().getWorkbenchWindow().getWorkbench().getOperationSupport().getOperationHistory().addOperationHistoryListener(new OperationHistoryListener());
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalToolBar(bars.getToolBarManager());
	}
	
	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(identifyBadSmellsAction);
		manager.add(applyRefactoringAction);
		manager.add(renameMethodAction);
		manager.add(saveResultsAction);
	}

	private void makeActions() {
		identifyBadSmellsAction = new Action() {
			public void run() {
				CompilationUnitCache.getInstance().clearCache();
				sliceTable = getTable();
				tableViewer.setContentProvider(new ViewContentProvider());
				applyRefactoringAction.setEnabled(true);
				renameMethodAction.setEnabled(true);
				saveResultsAction.setEnabled(true);
			}
		};
		identifyBadSmellsAction.setToolTipText("Identify Bad Smells");
		identifyBadSmellsAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
			getImageDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));
		identifyBadSmellsAction.setEnabled(false);
		
		saveResultsAction = new Action() {
			public void run() {
				saveResults();
			}
		};
		saveResultsAction.setToolTipText("Save Results");
		saveResultsAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
			getImageDescriptor(ISharedImages.IMG_ETOOL_SAVE_EDIT));
		saveResultsAction.setEnabled(false);
		
		applyRefactoringAction = new Action() {
			public void run() {
				IStructuredSelection selection = (IStructuredSelection)tableViewer.getSelection();
				ASTSlice slice = (ASTSlice)selection.getFirstElement();
				TypeDeclaration sourceTypeDeclaration = slice.getSourceTypeDeclaration();
				CompilationUnit sourceCompilationUnit = (CompilationUnit)sourceTypeDeclaration.getRoot();
				IFile sourceFile = slice.getIFile();
				Refactoring refactoring = new ExtractMethodRefactoring(sourceCompilationUnit, slice);
				MyRefactoringWizard wizard = new MyRefactoringWizard(refactoring, applyRefactoringAction);
				RefactoringWizardOpenOperation op = new RefactoringWizardOpenOperation(wizard); 
				try { 
					String titleForFailedChecks = ""; //$NON-NLS-1$ 
					op.run(getSite().getShell(), titleForFailedChecks); 
				} catch(InterruptedException e) {
					e.printStackTrace();
				}
				try {
					IJavaElement sourceJavaElement = JavaCore.create(sourceFile);
					JavaUI.openInEditor(sourceJavaElement);
				} catch (PartInitException e) {
					e.printStackTrace();
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		};
		applyRefactoringAction.setToolTipText("Apply Refactoring");
		applyRefactoringAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
				getImageDescriptor(ISharedImages.IMG_DEF_VIEW));
		applyRefactoringAction.setEnabled(false);
		
		renameMethodAction = new Action() {
			public void run() {
				IStructuredSelection selection = (IStructuredSelection)tableViewer.getSelection();
				ASTSlice slice = (ASTSlice)selection.getFirstElement();
				String methodName = slice.getExtractedMethodName();
				IInputValidator methodNameValidator = new MethodNameValidator();
				InputDialog dialog = new InputDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), "Rename Extracted Method", "Please enter a new name", methodName, methodNameValidator);
				dialog.open();
				if(dialog.getValue() != null) {
					slice.setExtractedMethodName(dialog.getValue());
					tableViewer.refresh();
				}
			}
		};
		renameMethodAction.setToolTipText("Rename Extracted Method");
		renameMethodAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
				getImageDescriptor(ISharedImages.IMG_OBJ_FILE));
		renameMethodAction.setEnabled(false);
		
		doubleClickAction = new Action() {
			public void run() {
				IStructuredSelection selection = (IStructuredSelection)tableViewer.getSelection();
				ASTSlice slice = (ASTSlice)selection.getFirstElement();
				IFile sourceFile = slice.getIFile();
				try {
					IJavaElement sourceJavaElement = JavaCore.create(sourceFile);
					ITextEditor sourceEditor = (ITextEditor)JavaUI.openInEditor(sourceJavaElement);
					Map<Position, String> positionMap = slice.getHighlightPositions();
					AnnotationModel annotationModel = (AnnotationModel)sourceEditor.getDocumentProvider().getAnnotationModel(sourceEditor.getEditorInput());
					Iterator<Annotation> annotationIterator = annotationModel.getAnnotationIterator();
					while(annotationIterator.hasNext()) {
						Annotation currentAnnotation = annotationIterator.next();
						if(currentAnnotation.getType().equals("org.eclipse.jdt.ui.occurrences")) {
							annotationModel.removeAnnotation(currentAnnotation);
						}
					}
					for(Position position : positionMap.keySet()) {
						Annotation annotation = new Annotation("org.eclipse.jdt.ui.occurrences", false, positionMap.get(position));
						annotationModel.addAnnotation(annotation, position);
					}
					List<Position> positions = new ArrayList<Position>(positionMap.keySet());
					Position firstPosition = positions.get(0);
					Position lastPosition = positions.get(positions.size()-1);
					int offset = firstPosition.getOffset();
					int length = lastPosition.getOffset() + lastPosition.getLength() - firstPosition.getOffset();
					sourceEditor.setHighlightRange(offset, length, true);
				} catch (PartInitException e) {
					e.printStackTrace();
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		};
	}

	private void hookDoubleClickAction() {
		tableViewer.addDoubleClickListener(new IDoubleClickListener() {
			public void doubleClick(DoubleClickEvent event) {
				doubleClickAction.run();
			}
		});
	}

	@Override
	public void setFocus() {
		tableViewer.getControl().setFocus();
	}

	public void dispose() {
		super.dispose();
		getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(selectionListener);
	}

	private ASTSlice[] getTable() {
		new ASTReader(selectedProject);
		final SystemObject systemObject = ASTReader.getSystemObject();
		final Set<ClassObject> classObjectsToBeExamined = new LinkedHashSet<ClassObject>();
		final Set<MethodObject> methodObjectsToBeExamined = new LinkedHashSet<MethodObject>();
		if(selectedPackage != null) {
			classObjectsToBeExamined.addAll(systemObject.getClassObjects(selectedPackage));
		}
		else if(selectedCompilationUnit != null) {
			classObjectsToBeExamined.addAll(systemObject.getClassObjects(selectedCompilationUnit));
		}
		else if(selectedType != null) {
			classObjectsToBeExamined.addAll(systemObject.getClassObjects(selectedType));
		}
		else if(selectedMethod != null) {
			methodObjectsToBeExamined.addAll(systemObject.getMethodObjects(selectedMethod));
		}
		else {
			classObjectsToBeExamined.addAll(systemObject.getClassObjects());
		}
		final List<PDGSliceUnion> extractedSlices = new ArrayList<PDGSliceUnion>();
		final List<PDGObjectSliceUnion> extractedObjectSlices = new ArrayList<PDGObjectSliceUnion>();
		IWorkbenchWindow window = getSite().getWorkbenchWindow();
		try {
			window.getWorkbench().getProgressService().run(true, true, new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
					if(!classObjectsToBeExamined.isEmpty()) {
						int workSize = 0;
						for(ClassObject classObject : classObjectsToBeExamined) {
							workSize += classObject.getNumberOfMethods();
						}
						monitor.beginTask("Identification of Extract Method refactoring opportunities", workSize);
						for(ClassObject classObject : classObjectsToBeExamined) {
							ListIterator<MethodObject> methodIterator = classObject.getMethodIterator();
							while(methodIterator.hasNext()) {
								if(monitor.isCanceled())
									throw new OperationCanceledException();
								MethodObject methodObject = methodIterator.next();
								processMethod(extractedSlices, extractedObjectSlices, classObject, methodObject);
								monitor.worked(1);
							}
						}
					}
					else if(!methodObjectsToBeExamined.isEmpty()) {
						int workSize = methodObjectsToBeExamined.size();
						monitor.beginTask("Identification of Extract Method refactoring opportunities", workSize);
						for(MethodObject methodObject : methodObjectsToBeExamined) {
							if(monitor.isCanceled())
								throw new OperationCanceledException();
							ClassObject classObject = systemObject.getClassObject(methodObject.getClassName());
							processMethod(extractedSlices, extractedObjectSlices, classObject, methodObject);
							monitor.worked(1);
						}
					}
					monitor.done();
				}
			});
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		ASTSlice[] table = new ASTSlice[extractedSlices.size() + extractedObjectSlices.size()];
		for(int i=0; i<extractedSlices.size(); i++) {
			ASTSlice astSlice = new ASTSlice(extractedSlices.get(i));
			table[i] = astSlice;
		}
		for(int i=0; i<extractedObjectSlices.size(); i++) {
			ASTSlice astSlice = new ASTSlice(extractedObjectSlices.get(i));
			table[extractedSlices.size() + i] = astSlice;
		}
		return table;
	}

	private void processMethod(final List<PDGSliceUnion> extractedSlices, final List<PDGObjectSliceUnion> extractedObjectSlices,
			ClassObject classObject, MethodObject methodObject) {
		if(methodObject.getMethodBody() != null) {
			ITypeRoot typeRoot = classObject.getITypeRoot();
			CompilationUnitCache.getInstance().lock(typeRoot);
			CFG cfg = new CFG(methodObject);
			PDG pdg = new PDG(cfg, classObject.getIFile(), classObject.getFieldsAccessedInsideMethod(methodObject));
			for(VariableDeclaration declaration : pdg.getVariableDeclarationsInMethod()) {
				PlainVariable variable = new PlainVariable(declaration);
				PDGSliceUnionCollection sliceUnionCollection = new PDGSliceUnionCollection(pdg, variable);
				for(PDGSliceUnion sliceUnion : sliceUnionCollection.getSliceUnions()) {
					extractedSlices.add(sliceUnion);
				}
			}
			for(VariableDeclaration declaration : pdg.getVariableDeclarationsAndAccessedFieldsInMethod()) {
				PlainVariable variable = new PlainVariable(declaration);
				PDGObjectSliceUnionCollection objectSliceUnionCollection = new PDGObjectSliceUnionCollection(pdg, variable);
				for(PDGObjectSliceUnion objectSliceUnion : objectSliceUnionCollection.getSliceUnions()) {
					extractedObjectSlices.add(objectSliceUnion);
				}
			}
			CompilationUnitCache.getInstance().releaseLock();
		}
	}

	private void saveResults() {
		FileDialog fd = new FileDialog(getSite().getWorkbenchWindow().getShell(), SWT.SAVE);
		fd.setText("Save Results");
        String[] filterExt = { "*.txt" };
        fd.setFilterExtensions(filterExt);
        String selected = fd.open();
        if(selected != null) {
        	try {
        		BufferedWriter out = new BufferedWriter(new FileWriter(selected));
        		Table table = tableViewer.getTable();
        		TableColumn[] columns = table.getColumns();
        		for(int i=0; i<columns.length; i++) {
        			if(i == columns.length-1)
        				out.write(columns[i].getText());
        			else
        				out.write(columns[i].getText() + "\t");
        		}
        		out.newLine();
        		for(int i=0; i<table.getItemCount(); i++) {
        			TableItem tableItem = table.getItem(i);
        			for(int j=0; j<table.getColumnCount(); j++) {
        				if(j == table.getColumnCount()-1)
        					out.write(tableItem.getText(j));
        				else
        					out.write(tableItem.getText(j) + "\t");
        			}
        			out.newLine();
        		}
        		out.close();
        	} catch (IOException e) {
        		e.printStackTrace();
        	}
        }
	}
}
