/* 
 * Spring Boot Database Admin - An automatically generated CRUD admin UI for Spring Boot apps
 * Copyright (C) 2023 Ailef (http://ailef.tech)
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package tech.ailef.dbadmin.external.controller;

import java.sql.ResultSetMetaData;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.id.IdentifierGenerationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import tech.ailef.dbadmin.external.DbAdmin;
import tech.ailef.dbadmin.external.DbAdminProperties;
import tech.ailef.dbadmin.external.dbmapping.DbAdminRepository;
import tech.ailef.dbadmin.external.dbmapping.DbObject;
import tech.ailef.dbadmin.external.dbmapping.DbObjectSchema;
import tech.ailef.dbadmin.external.dbmapping.query.DbQueryOutputField;
import tech.ailef.dbadmin.external.dbmapping.query.DbQueryResult;
import tech.ailef.dbadmin.external.dbmapping.query.DbQueryResultRow;
import tech.ailef.dbadmin.external.dto.CompareOperator;
import tech.ailef.dbadmin.external.dto.FacetedSearchRequest;
import tech.ailef.dbadmin.external.dto.LogsSearchRequest;
import tech.ailef.dbadmin.external.dto.PaginatedResult;
import tech.ailef.dbadmin.external.dto.QueryFilter;
import tech.ailef.dbadmin.external.dto.ValidationErrorsContainer;
import tech.ailef.dbadmin.external.exceptions.DbAdminException;
import tech.ailef.dbadmin.external.exceptions.DbAdminNotFoundException;
import tech.ailef.dbadmin.external.exceptions.InvalidPageException;
import tech.ailef.dbadmin.external.misc.Utils;
import tech.ailef.dbadmin.internal.model.ConsoleQuery;
import tech.ailef.dbadmin.internal.model.UserAction;
import tech.ailef.dbadmin.internal.model.UserSetting;
import tech.ailef.dbadmin.internal.repository.ConsoleQueryRepository;
import tech.ailef.dbadmin.internal.repository.UserSettingsRepository;
import tech.ailef.dbadmin.internal.service.UserActionService;

/**
 * The main DbAdmin controller that register most of the routes of the web interface.
 */
@Controller
@RequestMapping(value= {"/${dbadmin.baseUrl}", "/${dbadmin.baseUrl}/"})
public class DefaultDbAdminController {
	@Autowired
	private DbAdminProperties properties;
	
	@Autowired
	private DbAdminRepository repository;
	
	@Autowired
	private DbAdmin dbAdmin;
	
	@Autowired
	private UserActionService userActionService;
	
	@Autowired
	private ConsoleQueryRepository consoleQueryRepository;
	
	@Autowired
	private JdbcTemplate jdbTemplate; 

	@Autowired
	private UserSettingsRepository userSettingsRepo;
	
	/**
	 * Home page with list of schemas
	 * @param model
	 * @param query
	 * @return
	 */
	@GetMapping
	public String index(Model model, @RequestParam(required = false) String query) {
		
		List<DbObjectSchema> schemas = dbAdmin.getSchemas();
		if (query != null && !query.isBlank()) {
			schemas = schemas.stream().filter(s -> {
				return s.getClassName().toLowerCase().contains(query.toLowerCase())
					|| s.getTableName().toLowerCase().contains(query.toLowerCase());
			}).collect(Collectors.toList());
		}
		
		Map<String, List<DbObjectSchema>> groupedBy = 
			schemas.stream().collect(Collectors.groupingBy(s -> s.getBasePackage()));
		
		Map<String, Long> counts = 
			schemas.stream().collect(Collectors.toMap(s -> s.getClassName(), s -> repository.count(s)));
		
		model.addAttribute("schemas", groupedBy);
		model.addAttribute("query", query);
		model.addAttribute("counts", counts);
		model.addAttribute("activePage", "entities");
		model.addAttribute("title", "Entities | Index");
		
		return "home";
	}
	
	/**
	 * Lists the items of a schema by applying a variety of filters:
	 *  - query: fuzzy search
	 *  - otherParams: filterable fields
	 * Includes pagination and sorting options.
	 *  
	 * @param model
	 * @param className
	 * @param page
	 * @param query
	 * @param pageSize
	 * @param sortKey
	 * @param sortOrder
	 * @param otherParams
	 * @param request
	 * @param response
	 * @return
	 */
	@GetMapping("/model/{className}")
	public String list(Model model, @PathVariable String className,
			@RequestParam(required=false) Integer page, @RequestParam(required=false) String query,
			@RequestParam(required=false) Integer pageSize, @RequestParam(required=false) String sortKey, 
			@RequestParam(required=false) String sortOrder, @RequestParam MultiValueMap<String, String> otherParams,
			HttpServletRequest request,
			HttpServletResponse response) {
		
		if (page == null) page = 1;
		if (pageSize == null) pageSize = 50;
		
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		Set<QueryFilter> queryFilters = Utils.computeFilters(schema, otherParams);
		if (otherParams.containsKey("remove_field")) {
			List<String> fields = otherParams.get("remove_field");
			
			for (int i = 0; i < fields.size(); i++) {
				QueryFilter toRemove = 
					new QueryFilter(
						schema.getFieldByJavaName(fields.get(i)), 
						CompareOperator.valueOf(otherParams.get("remove_op").get(i).toUpperCase()), 
						otherParams.get("remove_value").get(i)
					);
				
				queryFilters.removeIf(f -> f.equals(toRemove));
			}
			
			FacetedSearchRequest filterRequest = new FacetedSearchRequest(queryFilters);
			MultiValueMap<String, String> parameterMap = filterRequest.computeParams();
			
			MultiValueMap<String, String> filteredParams = new LinkedMultiValueMap<>();
 			request.getParameterMap().entrySet().stream()
				.filter(e -> !e.getKey().startsWith("remove_") && !e.getKey().startsWith("filter_"))
				.forEach(e -> {
					filteredParams.putIfAbsent(e.getKey(), new ArrayList<>());
					for (String v : e.getValue()) {
						if (filteredParams.get(e.getKey()).isEmpty()) {
							filteredParams.get(e.getKey()).add(v);
						} else {
							filteredParams.get(e.getKey()).set(0, v);
						}
					}
				});
 			
 			filteredParams.putAll(parameterMap);
 			String queryString = Utils.getQueryString(filteredParams);
			String redirectUrl = request.getServletPath() + queryString; 
			return "redirect:" + redirectUrl.trim();
		}
		
		try {
			PaginatedResult<DbObject> result = null;
			if (query != null || !otherParams.isEmpty()) {
				result = repository.search(schema, query, page, pageSize, sortKey, sortOrder, queryFilters);
			} else {
				result = repository.findAll(schema, page, pageSize, sortKey, sortOrder);
			}
				
			model.addAttribute("title", "Entities | " + schema.getJavaClass().getSimpleName() + " | Index");
			model.addAttribute("page", result);
			model.addAttribute("schema", schema);
			model.addAttribute("activePage", "entities");
			model.addAttribute("sortKey", sortKey);
			model.addAttribute("query", query);
			model.addAttribute("sortOrder", sortOrder);
			model.addAttribute("activeFilters", queryFilters);
			return "model/list";
			
		} catch (InvalidPageException e) {
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		} catch (DbAdminException e) {
			model.addAttribute("error", e.getMessage());
			model.addAttribute("errorTitle", "Invalid request");
			model.addAttribute("schema", schema);
			model.addAttribute("activePage", "entities");
			model.addAttribute("sortKey", sortKey);
			model.addAttribute("query", query);
			model.addAttribute("sortOrder", sortOrder);
			model.addAttribute("activeFilters", queryFilters);
			return "model/list";
		}
	}
	
	/**
	 * Displays information about the schema
	 * @param model
	 * @param className
	 * @return
	 */
	@GetMapping("/model/{className}/schema")
	public String schema(Model model, @PathVariable String className) {
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		model.addAttribute("activePage", "entities");
		model.addAttribute("schema", schema);
		
		return "model/schema";
	}
	
	/**
	 * Shows a single item
	 * @param model
	 * @param className
	 * @param id
	 * @return
	 */
	@GetMapping("/model/{className}/show/{id}")
	public String show(Model model, @PathVariable String className, @PathVariable String id) {
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		Object pkValue = schema.getPrimaryKey().getType().parseValue(id);
		
		DbObject object = repository.findById(schema, pkValue).orElseThrow(() -> {
			return new DbAdminNotFoundException(
				schema.getJavaClass().getSimpleName() + " with ID " + id + " not found."
			);
		});
		
		model.addAttribute("title", "Entities | " + schema.getJavaClass().getSimpleName() + " | " + object.getDisplayName());
		model.addAttribute("object", object);
		model.addAttribute("activePage", "entities");
		model.addAttribute("schema", schema);
		
		return "model/show";
	}
	
	
	@GetMapping("/model/{className}/create")
	public String create(Model model, @PathVariable String className, RedirectAttributes attr) {
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		if (!schema.isCreateEnabled()) {
			attr.addFlashAttribute("errorTitle", "Unauthorized");
			attr.addFlashAttribute("error", "CREATE operations have been disabled on this type (" + schema.getJavaClass().getSimpleName() + ").");
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		}
		
		model.addAttribute("className", className);
		model.addAttribute("schema", schema);
		model.addAttribute("title", "Entities | " + schema.getJavaClass().getSimpleName() + " | Create");
		model.addAttribute("activePage", "entities");
		model.addAttribute("create", true);
		
		return "model/create";
	}
	
	@GetMapping("/model/{className}/edit/{id}")
	public String edit(Model model, @PathVariable String className, @PathVariable String id, RedirectAttributes attr) {
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		Object pkValue = schema.getPrimaryKey().getType().parseValue(id);
		
		if (!schema.isEditEnabled()) {
			attr.addFlashAttribute("errorTitle", "Unauthorized");
			attr.addFlashAttribute("error", "EDIT operations have been disabled on this type (" + schema.getJavaClass().getSimpleName() + ").");
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		}
		
		DbObject object = repository.findById(schema, pkValue).orElseThrow(() -> {
			return new DbAdminNotFoundException(
			  "Object " + className + " with id " + id + " not found"
			);
		});
		
		model.addAttribute("title", "Entities | " + schema.getJavaClass().getSimpleName() + " | Edit | " + object.getDisplayName());
		model.addAttribute("className", className);
		model.addAttribute("object", object);
		model.addAttribute("schema", schema);
		model.addAttribute("activePage", "entities");
		model.addAttribute("create", false);
		
		return "model/create";
	}
	
	@PostMapping(value="/model/{className}/delete/{id}")
	/**
	 * Delete a single row based on its primary key value
	 * @param className
	 * @param id
	 * @param attr
	 * @return
	 */
	public String delete(@PathVariable String className, @PathVariable String id, RedirectAttributes attr) {
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		if (!schema.isDeleteEnabled()) {
			attr.addFlashAttribute("errorTitle", "Unable to DELETE row");
			attr.addFlashAttribute("error", "DELETE operations have been disabled on this table.");
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		}
		
		try {
			repository.delete(schema, id);
		} catch (DataIntegrityViolationException e) {
			attr.addFlashAttribute("errorTitle", "Unable to DELETE row");
			attr.addFlashAttribute("error", e.getMessage());
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		}
		
		saveAction(new UserAction(schema.getTableName(), id, "DELETE", schema.getClassName()));
		attr.addFlashAttribute("message", "Deleted " + schema.getJavaClass().getSimpleName() + " with " 
				+ schema.getPrimaryKey().getName() + "=" + id);

		return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
	}
	
	@PostMapping(value="/model/{className}/delete")
	/**
	 * Delete multiple rows based on their primary key values
	 * @param className
	 * @param ids
	 * @param attr
	 * @return
	 */
	public String delete(@PathVariable String className, @RequestParam String[] ids, RedirectAttributes attr) {
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		if (!schema.isDeleteEnabled()) {
			attr.addFlashAttribute("errorTitle", "Unable to DELETE rows");
			attr.addFlashAttribute("error", "DELETE operations have been disabled on this table.");
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		}
		
		int countDeleted = 0;
		for (String id : ids) {
			try {
				repository.delete(schema, id);
				countDeleted += 1;
			} catch (DataIntegrityViolationException e) {
				attr.addFlashAttribute("error", e.getMessage());
			}
		}
		
		if (countDeleted > 0)
			attr.addFlashAttribute("message", "Deleted " + countDeleted + " of " + ids.length + " items");
		
		for (String id : ids) {
			saveAction(new UserAction(schema.getTableName(), id, "DELETE", schema.getClassName()));
		}
		
		return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
	}
	
	@PostMapping(value="/model/{className}/create")
	public String store(@PathVariable String className,
			@RequestParam MultiValueMap<String, String> formParams,
			@RequestParam Map<String, MultipartFile> files,
			RedirectAttributes attr) {
		// Extract all parameters that have exactly 1 value,
		// as these will be the raw values for the object that is being
		// created.
		// The remaining parmeters which have more than 1 value
		// are IDs in a many-to-many relationship and need to be
		// handled separately
		Map<String, String> params = new HashMap<>();
		for (String param : formParams.keySet()) {
			if (!param.endsWith("[]")) {
				params.put(param, formParams.getFirst(param));
			}
		}
		
		Map<String, List<String>> multiValuedParams = new HashMap<>();
		for (String param : formParams.keySet()) {
			if (param.endsWith("[]")) {
				List<String> list = formParams.get(param);
				// If the request contains only 1 parameter value, it's the empty
				// value that signifies just the presence of the field (e.g. the
				// user might've deleted all the value)
				if (list.size() == 1) {
					multiValuedParams.put(param, new ArrayList<>());
				} else {
					list.removeIf(f -> f.isBlank());
					multiValuedParams.put(
						param, 
						list
					);
				}
			}
		}
		
 		String c = params.get("__dbadmin_create");
		if (c == null) {
			throw new ResponseStatusException(
				HttpStatus.BAD_REQUEST, "Missing required param __dbadmin_create"
			);
		}
		
		boolean create = Boolean.parseBoolean(c);
		
		DbObjectSchema schema = dbAdmin.findSchemaByClassName(className);
		
		if (!schema.isCreateEnabled() && create) {
			attr.addFlashAttribute("errorTitle", "Unauthorized");
			attr.addFlashAttribute("error", "CREATE operations have been disabled on this type (" + schema.getJavaClass().getSimpleName() + ").");
			return "redirect:/" + properties.getBaseUrl() + "/model/" + className;
		}

		String pkValue = params.get(schema.getPrimaryKey().getName());
		if (pkValue == null || pkValue.isBlank()) {
			pkValue = null;
		}
		
		try {
			if (pkValue == null) {
				Object newPrimaryKey = repository.create(schema, params, files, pkValue);
				repository.attachManyToMany(schema, newPrimaryKey, multiValuedParams);				
				pkValue = newPrimaryKey.toString();
				attr.addFlashAttribute("message", "Item created successfully.");
				saveAction(new UserAction(schema.getTableName(), pkValue, "CREATE", schema.getClassName()));
			} else {
				Object parsedPkValue = schema.getPrimaryKey().getType().parseValue(pkValue);

				Optional<DbObject> object = repository.findById(schema, parsedPkValue);
				
				if (!object.isEmpty()) {
					if (create) {
						attr.addFlashAttribute("errorTitle", "Unable to create item");
						attr.addFlashAttribute("error", "Item with id " + object.get().getPrimaryKeyValue() + " already exists.");
						attr.addFlashAttribute("params", params);
					} else {
						repository.update(schema, params, files);
						repository.attachManyToMany(schema, parsedPkValue, multiValuedParams);
						attr.addFlashAttribute("message", "Item saved successfully.");
						saveAction(new UserAction(schema.getTableName(), parsedPkValue.toString(), "EDIT", schema.getClassName()));
					}
				} else {
					Object newPrimaryKey = repository.create(schema, params, files, pkValue);
					repository.attachManyToMany(schema, newPrimaryKey, multiValuedParams);
					attr.addFlashAttribute("message", "Item created successfully");
					saveAction(new UserAction(schema.getTableName(), pkValue, "CREATE", schema.getClassName()));
				}
			}
		} catch (DataIntegrityViolationException | UncategorizedSQLException | IdentifierGenerationException e) {
			attr.addFlashAttribute("errorTitle", "Error");
			attr.addFlashAttribute("error", e.getMessage());
			attr.addFlashAttribute("params", params);
		} catch (ConstraintViolationException e) {
			attr.addFlashAttribute("errorTitle", "Validation error");
			attr.addFlashAttribute("error", "See below for details");
			attr.addFlashAttribute("validationErrors", new ValidationErrorsContainer(e));
			attr.addFlashAttribute("params", params);
		} catch (DbAdminException e) {
			attr.addFlashAttribute("errorTitle", "Error");
			attr.addFlashAttribute("error", e.getMessage());
			attr.addFlashAttribute("params", params);
		} catch (TransactionSystemException e) {
			if (e.getRootCause() instanceof ConstraintViolationException) {
				ConstraintViolationException ee = (ConstraintViolationException)e.getRootCause();
				attr.addFlashAttribute("errorTitle", "Validation error");
				attr.addFlashAttribute("error", "See below for details");
				attr.addFlashAttribute("validationErrors", new ValidationErrorsContainer(ee));
				attr.addFlashAttribute("params", params);
			} else {
				throw new RuntimeException(e);
			}
		}


		if (attr.getFlashAttributes().containsKey("error")) {
			if (create)
				return "redirect:/" + properties.getBaseUrl() + "/model/" + schema.getClassName() + "/create";
			else
				return "redirect:/" + properties.getBaseUrl() + "/model/" + schema.getClassName() + "/edit/" + pkValue;
		} else {
			return "redirect:/" + properties.getBaseUrl() + "/model/" + schema.getClassName() + "/show/" + pkValue;
		}
	}
	
	@GetMapping("/logs")
	public String logs(Model model, LogsSearchRequest searchRequest) {
		model.addAttribute("activePage", "logs");
		model.addAttribute(
			"page", 
			userActionService.findActions(searchRequest)
		);
		model.addAttribute("schemas", dbAdmin.getSchemas());
		model.addAttribute("searchRequest", searchRequest);
		return "logs";
	}
	
	
	@GetMapping("/settings")
	public String settings(Model model) {
		model.addAttribute("activePage", "settings");
		return "settings/settings";
	}
	
	@GetMapping("/help")
	public String about(Model model) {
		model.addAttribute("activePage", "help");
		return "help";
	}
	
	@GetMapping("/console/new")
	public String consoleNew(Model model) {
		if (!properties.isSqlConsoleEnabled()) {
			throw new DbAdminException("SQL console not enabled");
		}
		
		model.addAttribute("activePage", "console");
		
		ConsoleQuery q = new ConsoleQuery();
		consoleQueryRepository.save(q);
		return "redirect:/" + properties.getBaseUrl() + "/console/run/" + q.getId();
	}
	
	@GetMapping("/console")
	public String console(Model model) {
		if (!properties.isSqlConsoleEnabled()) {
			throw new DbAdminException("SQL console not enabled");
		}
		
		List<ConsoleQuery> tabs = consoleQueryRepository.findAll();
		
		if (tabs.isEmpty()) {
			ConsoleQuery q = new ConsoleQuery();
			consoleQueryRepository.save(q);
			tabs.add(q);
			return "redirect:/" + properties.getBaseUrl() + "/console/run/" + q.getId();
		} else {
			return "redirect:/" + properties.getBaseUrl() + "/console/run/" + tabs.get(0).getId();
		}
	}
	
	@PostMapping("/console/delete/{queryId}")
	public String consoleDelete(@PathVariable String queryId, Model model) {
		if (!properties.isSqlConsoleEnabled()) {
			throw new DbAdminException("SQL console not enabled");
		}
		
		consoleQueryRepository.deleteById(queryId);
		return "redirect:/" + properties.getBaseUrl() + "/console";
	}
	
	
	
	@GetMapping("/console/run/{queryId}")
	public String consoleRun(Model model, @RequestParam(required = false) String query,
			@RequestParam(required = false) String queryTitle,
			@PathVariable String queryId) {
		long startTime = System.currentTimeMillis();
		
		if (!properties.isSqlConsoleEnabled()) {
			throw new DbAdminException("SQL console not enabled");
		}
		
		ConsoleQuery activeQuery = consoleQueryRepository.findById(queryId).orElseThrow(() -> {
			return new DbAdminNotFoundException("Query with ID " + queryId + " not found.");
		});
		
		if (query != null && !query.isBlank()) {
			activeQuery.setSql(query);
		}
		if (queryTitle != null && !queryTitle.isBlank()) {
			activeQuery.setTitle(queryTitle);
		}
		
		activeQuery.setUpdatedAt(LocalDateTime.now());
		consoleQueryRepository.save(activeQuery);
		
		model.addAttribute("activePage", "console");
		model.addAttribute("activeQuery", activeQuery);
		
		List<ConsoleQuery> tabs = consoleQueryRepository.findAll();
		model.addAttribute("tabs", tabs);
		
		if (activeQuery.getSql() != null && !activeQuery.getSql().isBlank()) {
			try {
				List<DbQueryResultRow> results = jdbTemplate.query(activeQuery.getSql(), (rs, rowNum) -> {
					Map<DbQueryOutputField, Object> result = new HashMap<>();
					
					ResultSetMetaData metaData = rs.getMetaData();
					int cols = metaData.getColumnCount();
					
					for (int i = 0; i < cols; i++) {
						Object o = rs.getObject(i + 1);
						String columnName = metaData.getColumnName(i + 1);
						String tableName = metaData.getTableName(i + 1);
						DbQueryOutputField field = new DbQueryOutputField(columnName, tableName, dbAdmin);
						
						result.put(field, o);
					}
					
					return new DbQueryResultRow(result, query);
				});
				model.addAttribute("results", new DbQueryResult(results));
			} catch (DataAccessException e) {
				model.addAttribute("error", e.getMessage());
			}
		}

		double elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0;
		model.addAttribute("elapsedTime", new DecimalFormat("0.0#").format(elapsedTime));
		return "console";
	}
	
	@GetMapping("/settings/appearance")
	public String settingsAppearance(Model model) {
		model.addAttribute("activePage", "settings");
		return "settings/appearance";
	}
	
	@PostMapping("/settings")
	public String settings(@RequestParam Map<String, String> params, Model model) {
		String next = params.getOrDefault("next", "settings/settings");
		
		for (String paramName : params.keySet()) {
			if (paramName.equals("next")) continue;
			
			userSettingsRepo.save(new UserSetting(paramName, params.get(paramName)));
		}
		model.addAttribute("activePage", "settings");
		return next;
	}
	
	private UserAction saveAction(UserAction action) {
		return userActionService.save(action);
	}
}
