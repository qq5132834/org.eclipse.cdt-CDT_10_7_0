/*******************************************************************************
 * Copyright (c) 2010, 2016 Texas Instruments, Freescale Semiconductor and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Texas Instruments, Freescale Semiconductor - initial API and implementation
 *     Alvaro Sanchez-Leon (Ericsson AB) - Each memory context needs a different MemoryRetrieval (Bug 250323)
 *     Alvaro Sanchez-Leon (Ericsson AB) - [Memory] Support 16 bit addressable size (Bug 426730)
 *     Anders Dahlberg (Ericsson)  - Need additional API to extend support for memory spaces (Bug 431627)
 *     Alvaro Sanchez-Leon (Ericsson AB)  - Need additional API to extend support for memory spaces (Bug 431627)
 *******************************************************************************/
package org.eclipse.cdt.dsf.gdb.internal.memory;

import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.cdt.debug.core.model.provisional.IMemorySpaceAwareMemoryBlock;
import org.eclipse.cdt.debug.core.model.provisional.IMemorySpaceAwareMemoryBlockRetrieval;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.DsfRunnable;
import org.eclipse.cdt.dsf.concurrent.Query;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.model.DsfMemoryBlock;
import org.eclipse.cdt.dsf.debug.model.DsfMemoryBlockRetrieval;
import org.eclipse.cdt.dsf.debug.service.IMemory.IMemoryDMContext;
import org.eclipse.cdt.dsf.debug.service.IMemorySpaces;
import org.eclipse.cdt.dsf.debug.service.IMemorySpaces.IMemorySpaceDMContext;
import org.eclipse.cdt.dsf.debug.service.IMemorySpaces2;
import org.eclipse.cdt.dsf.gdb.internal.GdbPlugin;
import org.eclipse.cdt.dsf.gdb.internal.memory.GdbMemoryBlock.MemorySpaceDMContext;
import org.eclipse.cdt.dsf.gdb.service.IGDBMemory;
import org.eclipse.cdt.dsf.gdb.service.IGDBMemory2;
import org.eclipse.cdt.dsf.service.DsfServices;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.internal.core.XmlUtil;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IMemoryBlockExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.util.tracker.ServiceTracker;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A specialization of the DSF memory block retrieval implementation supporting
 * memory spaces. The memory space support is provisional, thus this class is
 * internal.
 *
 * @author Alain Lee and John Cortell
 */
public class GdbMemoryBlockRetrieval extends DsfMemoryBlockRetrieval implements IMemorySpaceAwareMemoryBlockRetrieval {

	private final ServiceTracker<IMemorySpaces, IMemorySpaces> fMemorySpaceServiceTracker;

	// No need to use the constants in our base class. Serializing and
	// recreating the blocks is done entirely by us
	private static final String MEMORY_BLOCK_EXPRESSION_LIST = "memoryBlockExpressionList"; //$NON-NLS-1$
	private static final String ATTR_EXPRESSION_LIST_CONTEXT = "context"; //$NON-NLS-1$
	private static final String MEMORY_BLOCK_EXPRESSION = "gdbmemoryBlockExpression"; //$NON-NLS-1$
	private static final String ATTR_MEMORY_BLOCK_EXPR_LABEL = "label"; //$NON-NLS-1$
	private static final String ATTR_MEMORY_BLOCK_EXPR_ADDRESS = "address"; //$NON-NLS-1$
	private static final String ATTR_MEMORY_BLOCK_MEMORY_SPACE_ID = "memorySpaceID"; //$NON-NLS-1$

	/** see comment in base class */
	private static final String CONTEXT_RESERVED = "reserved-for-future-use"; //$NON-NLS-1$

	/**
	 * Constructor
	 */
	public GdbMemoryBlockRetrieval(String modelId, ILaunchConfiguration config, DsfSession session)
			throws DebugException {
		super(modelId, config, session);

		BundleContext bundle = GdbPlugin.getBundleContext();

		// Create a tracker for the memory spaces service
		String filter = DsfServices.createServiceFilter(IMemorySpaces.class, session.getId());
		try {
			fMemorySpaceServiceTracker = new ServiceTracker<>(bundle, bundle.createFilter(filter), null);
		} catch (InvalidSyntaxException e) {
			throw new DebugException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
					"Error creating service filter.", e)); //$NON-NLS-1$
		}

		fMemorySpaceServiceTracker.open();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.dsf.debug.model.DsfMemoryBlockRetrieval#getExtendedMemoryBlock(java.lang.String, java.lang.Object)
	 */
	@Override
	public IMemoryBlockExtension getExtendedMemoryBlock(String expression, Object context) throws DebugException {

		String memorySpaceID = null;

		// Determine if the expression has memory space information
		IDMContext dmc = null;
		if (context instanceof IDMContext) {
			dmc = (IDMContext) context;
		} else {
			if (context instanceof IAdaptable) {
				dmc = ((IAdaptable) context).getAdapter(IDMContext.class);
			}
		}

		if (dmc != null) {
			DecodeResult result = decodeMemorySpaceExpression(dmc, expression);
			expression = result.getExpression();
			memorySpaceID = result.getMemorySpaceId();
		}

		return getMemoryBlock(expression, context, memorySpaceID);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.internal.core.model.provisional.IMemorySpaceAwareMemoryBlockRetrieval#getExtendedMemoryBlock(java.lang.String, java.lang.Object, java.lang.String)
	 */
	@Override
	public IMemorySpaceAwareMemoryBlock getMemoryBlock(String expression, Object context, String memorySpaceID)
			throws DebugException {
		// Drill for the actual DMC
		IMemoryDMContext memoryDmc = null;
		IDMContext dmc = null;
		if (context instanceof IAdaptable) {
			dmc = ((IAdaptable) context).getAdapter(IDMContext.class);
			if (dmc != null) {
				memoryDmc = DMContexts.getAncestorOfType(dmc, IMemoryDMContext.class);
			}
		}

		if (memoryDmc == null) {
			return null;
		}

		//Adjust the memory context to use memory spaces when available
		if (memoryDmc instanceof IMemorySpaceDMContext) {
			// The memory space ids should match
			assert (memorySpaceID != null);
			assert (memorySpaceID.equals(((IMemorySpaceDMContext) memoryDmc).getMemorySpaceId()));
		} else {
			if (memorySpaceID != null && memorySpaceID.length() > 0) {
				memoryDmc = new MemorySpaceDMContext(getSession().getId(), memorySpaceID, memoryDmc);
			}
		}

		// The block start address (supports 64-bit processors)
		BigInteger blockAddress;

		/*
		 * See if the expression is a simple numeric value; if it is, we can
		 * avoid some costly processing (calling the back-end to resolve the
		 * expression and obtain an address)
		 */
		try {
			// First, assume a decimal address
			int base = 10;
			int offset = 0;

			// Check for "hexadecimality"
			if (expression.startsWith("0x") || expression.startsWith("0X")) { //$NON-NLS-1$//$NON-NLS-2$
				base = 16;
				offset = 2;
			}
			// Check for "binarity"
			else if (expression.startsWith("0b")) { //$NON-NLS-1$
				base = 2;
				offset = 2;
			}
			// Check for "octality"
			else if (expression.startsWith("0")) { //$NON-NLS-1$
				base = 8;
				offset = 1;
			}
			// Now, try to parse the expression. If a NumberFormatException is
			// thrown, then it wasn't a simple numerical expression and we go
			// to plan B (attempt an expression evaluation)
			blockAddress = new BigInteger(expression.substring(offset), base);

		} catch (NumberFormatException nfexc) {
			// OK, expression is not a simple, absolute numeric value;
			// try to resolve as an expression.
			// In case of failure, simply return 'null'

			// Resolve the expression
			blockAddress = resolveMemoryAddress(dmc, expression);
			if (blockAddress == null) {
				return null;
			}
		}

		// check for block address exceeding maximum allowed address value
		int addressSize = getAddressSize(memoryDmc);
		BigInteger endAddress = BigInteger.ONE.shiftLeft(addressSize * 8).subtract(BigInteger.ONE);
		if (endAddress.compareTo(blockAddress) < 0) {
			throw new DebugException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1,
					MessageFormat.format(Messages.Err_ExceedsMaxAddress, expression, endAddress.toString(16)), null));
		}

		/*
		 * At this point, we only resolved the requested memory block
		 * start address and we have no idea of the block's length.
		 *
		 * The renderer will provide this information when it calls
		 * getBytesFromAddress() i.e. after the memory block holder has
		 * been instantiated.
		 *
		 * The down side is that every time we switch renderer, for the
		 * same memory block, a trip to the target could result. However,
		 * the memory request cache should save the day.
		 */
		return new GdbMemoryBlock(this, memoryDmc, getModelId(), expression, blockAddress,
				getAddressableSize(memoryDmc), 0, memorySpaceID);
	}

	/*
	 * implementation of
	 *    @see org.eclipse.cdt.debug.internal.core.model.provisional.IMemorySpaceManagement#getMemorySpaces(Object context)
	 */
	@Override
	public void getMemorySpaces(final Object context, final GetMemorySpacesRequest request) {
		try {
			getExecutor().execute(new DsfRunnable() {
				@Override
				public void run() {
					IDMContext dmc = null;
					if (context instanceof IAdaptable) {
						dmc = ((IAdaptable) context).getAdapter(IDMContext.class);
						if (dmc != null) {
							IMemorySpaces service = fMemorySpaceServiceTracker.getService();
							if (service != null) {
								service.getMemorySpaces(dmc, new DataRequestMonitor<String[]>(getExecutor(), null) {
									@Override
									protected void handleCompleted() {
										// Store the result
										if (isSuccess()) {
											request.setMemorySpaces(getData());
										} else {
											request.setStatus(getStatus());
										}
										request.done();
									}
								});
								return;
							}
						}
					}

					// If we get here, something didn't work as expected
					request.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
							"Unable to get memory spaces", null)); //$NON-NLS-1$
					request.done();
				}
			});
		} catch (RejectedExecutionException e) {
			request.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
					"Unable to get memory spaces", null)); //$NON-NLS-1$
			request.done();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.internal.core.model.provisional.IMemorySpaceAwareMemoryBlockRetrieval#encodeAddress(java.lang.String, java.lang.String)
	 */
	@Override
	public String encodeAddress(String expression, String memorySpaceID) {
		String result = null;
		IMemorySpaces service = fMemorySpaceServiceTracker.getService();
		if (service != null) {
			// the service can tell us to use our default encoding by returning null
			result = service.encodeAddress(expression, memorySpaceID);
		}
		if (result == null) {
			// default encoding
			result = memorySpaceID + ':' + expression;
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.internal.core.model.provisional.IMemorySpaceAwareMemoryBlockRetrieval#decodeAddress(java.lang.String)
	 */
	@Override
	public DecodeResult decodeAddress(String str) throws CoreException {
		IMemorySpaces service = fMemorySpaceServiceTracker.getService();
		if (service != null) {
			final IMemorySpaces.DecodeResult result = service.decodeAddress(str);
			if (result != null) { // service can return null to tell use to use default decoding
				return new DecodeResult() {
					@Override
					public String getMemorySpaceId() {
						return result.getMemorySpaceId();
					}

					@Override
					public String getExpression() {
						return result.getExpression();
					}
				};
			}
		}

		// default decoding
		final String memorySpaceID;
		final String expression;
		int index = str.indexOf(':');
		if (index == -1) {
			//Unknown parsing, may not use memory spaces
			memorySpaceID = null;
			expression = str;
		} else {
			memorySpaceID = str.substring(0, index);
			expression = (index < str.length() - 1) ? str.substring(index + 1) : ""; //$NON-NLS-1$
		}

		return new DecodeResult() {
			@Override
			public String getMemorySpaceId() {
				return memorySpaceID;
			}

			@Override
			public String getExpression() {
				return expression;
			}
		};

	}

	/**
	 * Decode the received expression by
	 * First, decoding the string directly
	 * Second, if the memory space is not found in the expression string, use the Memory service to use some help from gdb
	 */
	private DecodeResult decodeMemorySpaceExpression(final IDMContext dmc, final String expression)
			throws DebugException {
		DecodeResult decodeResult;
		try {
			decodeResult = decodeAddress(expression);
		} catch (CoreException e1) {
			throw new DebugException(e1.getStatus());
		}

		if (decodeResult.getMemorySpaceId() != null) {
			//memory space found in expression
			return decodeResult;
		}

		//
		final IMemorySpaces service = fMemorySpaceServiceTracker.getService();
		if (service instanceof IMemorySpaces2) {
			final IMemorySpaces2 memSpaceService = (IMemorySpaces2) service;

			Query<IMemorySpaces.DecodeResult> query = new Query<>() {
				@Override
				protected void execute(final DataRequestMonitor<IMemorySpaces.DecodeResult> drm) {
					memSpaceService.decodeExpression(dmc, expression, drm);
				}
			};

			getExecutor().execute(query);
			try {
				final IMemorySpaces.DecodeResult result = query.get();
				decodeResult = new DecodeResult() {
					@Override
					public String getMemorySpaceId() {
						return result.getMemorySpaceId();
					}

					@Override
					public String getExpression() {
						return result.getExpression();
					}
				};
			} catch (InterruptedException e) {
				throw new DebugException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
						"Error evaluating memory space expression (InterruptedException).", e)); //$NON-NLS-1$

			} catch (ExecutionException e) {
				throw new DebugException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
						"Error evaluating memory space expression (ExecutionException).", e)); //$NON-NLS-1$
			}

		}

		return decodeResult;
	}

	ServiceTracker<IMemorySpaces, IMemorySpaces> getMemorySpaceServiceTracker() {
		return fMemorySpaceServiceTracker;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.dsf.debug.model.DsfMemoryBlockRetrieval#getMemento()
	 */
	@Override
	public String getMemento() throws CoreException {
		IMemoryBlock[] blocks = DebugPlugin.getDefault().getMemoryBlockManager().getMemoryBlocks(this);
		Document document = DebugPlugin.newDocument();
		Element expressionList = document.createElement(MEMORY_BLOCK_EXPRESSION_LIST);
		expressionList.setAttribute(ATTR_EXPRESSION_LIST_CONTEXT, CONTEXT_RESERVED);
		for (IMemoryBlock block : blocks) {
			if (block instanceof IMemoryBlockExtension) {
				IMemoryBlockExtension memoryBlock = (IMemoryBlockExtension) block;
				Element expression = document.createElement(MEMORY_BLOCK_EXPRESSION);
				expression.setAttribute(ATTR_MEMORY_BLOCK_EXPR_ADDRESS, memoryBlock.getBigBaseAddress().toString());
				if (block instanceof IMemorySpaceAwareMemoryBlock) {
					String memorySpaceID = ((IMemorySpaceAwareMemoryBlock) memoryBlock).getMemorySpaceID();
					if (memorySpaceID != null) {
						expression.setAttribute(ATTR_MEMORY_BLOCK_MEMORY_SPACE_ID, memorySpaceID);

						// What we return from GdbMemoryBlock#getExpression()
						// is the encoded representation. We need to decode it
						// to get the original expression used to create the block
						DecodeResult result = ((IMemorySpaceAwareMemoryBlockRetrieval) memoryBlock
								.getMemoryBlockRetrieval()).decodeAddress(memoryBlock.getExpression());
						expression.setAttribute(ATTR_MEMORY_BLOCK_EXPR_LABEL, result.getExpression());
					} else {
						expression.setAttribute(ATTR_MEMORY_BLOCK_EXPR_LABEL, memoryBlock.getExpression());
					}
				} else {
					assert false; // should never happen (see getExtendedMemoryBlock()), but we can handle it.
					expression.setAttribute(ATTR_MEMORY_BLOCK_EXPR_LABEL, memoryBlock.getExpression());
				}
				expressionList.appendChild(expression);
			}
		}
		document.appendChild(expressionList);
		return XmlUtil.toString(document);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.dsf.debug.model.DsfMemoryBlockRetrieval#createBlocksFromConfiguration(org.eclipse.cdt.dsf.debug.service.IMemory.IMemoryDMContext, java.lang.String)
	 */
	@Override
	protected void createBlocksFromConfiguration(final IMemoryDMContext memoryCtx, String memento)
			throws CoreException {

		// Parse the memento and validate its type
		Element root = DebugPlugin.parseDocument(memento);
		if (!root.getNodeName().equalsIgnoreCase(MEMORY_BLOCK_EXPRESSION_LIST)) {
			IStatus status = new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugPlugin.INTERNAL_ERROR,
					"Memory monitor initialization: invalid memento", null);//$NON-NLS-1$
			throw new CoreException(status);
		}

		// Process the block list specific to this memory context
		// FIXME: (Bug228573) We only process the first entry...
		if (root.getAttribute(ATTR_EXPRESSION_LIST_CONTEXT).equals(CONTEXT_RESERVED)) {
			List<IMemoryBlock> blocks = new ArrayList<>();
			NodeList expressionList = root.getChildNodes();
			int length = expressionList.getLength();
			for (int i = 0; i < length; ++i) {
				IMemoryDMContext memoryContext = memoryCtx;
				Node node = expressionList.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					Element entry = (Element) node;
					if (entry.getNodeName().equalsIgnoreCase(MEMORY_BLOCK_EXPRESSION)) {
						String label = entry.getAttribute(ATTR_MEMORY_BLOCK_EXPR_LABEL);
						String address = entry.getAttribute(ATTR_MEMORY_BLOCK_EXPR_ADDRESS);

						String memorySpaceID = null;
						if (entry.hasAttribute(ATTR_MEMORY_BLOCK_MEMORY_SPACE_ID)) {
							memorySpaceID = entry.getAttribute(ATTR_MEMORY_BLOCK_MEMORY_SPACE_ID);
							if (memorySpaceID.length() == 0) {
								memorySpaceID = null;
								assert false : "should have either no memory space or a valid (non-empty) ID"; //$NON-NLS-1$
							} else {
								if (memoryContext instanceof IMemorySpaceDMContext) {
									//The context is already a memory space context, make sure the ids are consistent
									assert (((IMemorySpaceDMContext) memoryContext).getMemorySpaceId()
											.equals(memorySpaceID));
								} else {
									//Use a memory space context if the memory space id is valid
									memoryContext = new MemorySpaceDMContext(getSession().getId(), memorySpaceID,
											memoryContext);
								}
							}
						}

						BigInteger blockAddress = new BigInteger(address);
						DsfMemoryBlock block = new GdbMemoryBlock(this, memoryContext, getModelId(), label,
								blockAddress, getAddressableSize(memoryContext), 0, memorySpaceID);
						blocks.add(block);
					}
				}
			}
			DebugPlugin.getDefault().getMemoryBlockManager()
					.addMemoryBlocks(blocks.toArray(new IMemoryBlock[blocks.size()]));
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.model.provisional.IMemorySpaceAwareMemoryBlockRetrieval#creatingBlockRequiresMemorySpaceID()
	 */
	@Override
	public boolean creatingBlockRequiresMemorySpaceID() {
		IMemorySpaces service = fMemorySpaceServiceTracker.getService();
		if (service != null) {
			return service.creatingBlockRequiresMemorySpaceID();
		}
		return false;
	}

	private int getAddressableSize(IMemoryDMContext context) {
		IGDBMemory2 memoryService = (IGDBMemory2) getServiceTracker().getService();

		if (memoryService != null && context != null) {
			return memoryService.getAddressableSize(context);
		}

		return super.getAddressableSize();
	}

	private int getAddressSize(IMemoryDMContext context) {
		IGDBMemory memoryService = (IGDBMemory) getServiceTracker().getService();
		if (memoryService != null && context != null) {
			return memoryService.getAddressSize(context);
		}
		return super.getAddressSize();
	}
}
