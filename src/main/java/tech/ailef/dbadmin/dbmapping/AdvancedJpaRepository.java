package tech.ailef.dbadmin.dbmapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import tech.ailef.dbadmin.dto.QueryFilter;

@SuppressWarnings("rawtypes")
public class AdvancedJpaRepository extends SimpleJpaRepository {

	private EntityManager entityManager;
	
	private DbObjectSchema schema;
	
	@SuppressWarnings("unchecked")
	public AdvancedJpaRepository(DbObjectSchema schema, EntityManager em) {
		super(schema.getJavaClass(), em);
		this.entityManager = em;
		this.schema = schema;
	}
	
	@SuppressWarnings("unchecked")
	public long count(String q, Set<QueryFilter> queryFilters) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery query = cb.createQuery(Long.class);
        Root root = query.from(schema.getJavaClass());

        List<Predicate> finalPredicates = buildPredicates(q, queryFilters, cb, root);
        
        query.select(cb.count(root.get(schema.getPrimaryKey().getName())))
            .where(
        		cb.and(
                		finalPredicates.toArray(new Predicate[finalPredicates.size()])
                	)
        		);
        
        Object o = entityManager.createQuery(query).getSingleResult();
        return (Long)o;
	}
	
	@SuppressWarnings("unchecked")
	public List<Object> search(String q, int page, int pageSize, String sortKey, String sortOrder, Set<QueryFilter> filters) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery query = cb.createQuery(schema.getJavaClass());
        Root root = query.from(schema.getJavaClass());
        
        List<Predicate> finalPredicates = buildPredicates(q, filters, cb, root);
        
        query.select(root)
            .where(
            	cb.and(
            		finalPredicates.toArray(new Predicate[finalPredicates.size()]) // query search on String fields
            	)
            	
            );
        if (sortKey !=  null)
        	query.orderBy(sortOrder.equals("DESC") ? cb.desc(root.get(sortKey)) : cb.asc(root.get(sortKey)));
        
        return entityManager.createQuery(query).setMaxResults(pageSize)
        			.setFirstResult((page - 1) * pageSize).getResultList();
	}
	
	private List<Predicate> buildPredicates(String q, Set<QueryFilter> queryFilters,
			CriteriaBuilder cb, Path root) {
		List<Predicate> finalPredicates = new ArrayList<>();
        
        List<DbField> stringFields = 
        	schema.getSortedFields().stream().filter(f -> f.getType() == DbFieldType.STRING)
        			.collect(Collectors.toList());
        
        List<Predicate> queryPredicates = new ArrayList<>();
        if (q != null) {
	        for (DbField f : stringFields) {
	        	Path path = root.get(f.getJavaName());
	        	queryPredicates.add(cb.like(cb.lower(cb.toString(path)), "%" + q.toLowerCase() + "%"));
	        }
	        
	        Predicate queryPredicate = cb.or(queryPredicates.toArray(new Predicate[queryPredicates.size()]));
	        finalPredicates.add(queryPredicate);
        }

        for (QueryFilter filter  : queryFilters) {
        	String op = filter.getOp();
        	String field = filter.getField();
        	String value = filter.getValue();
        		
			if (op.equalsIgnoreCase("equals")) {
				finalPredicates.add(cb.equal(cb.lower(cb.toString(root.get(field))), value.toLowerCase()));
			} else if (op.equalsIgnoreCase("contains")) {
				finalPredicates.add(
					cb.like(cb.lower(cb.toString(root.get(field))), "%" + value.toLowerCase() + "%")
				);
			} else if (op.equalsIgnoreCase("eq")) {
				finalPredicates.add(
					cb.equal(root.get(field), value)
				);
			} else if (op.equalsIgnoreCase("gt")) {
				finalPredicates.add(
					cb.greaterThan(root.get(field), value)
				);
			} else if (op.equalsIgnoreCase("lt")) {
				finalPredicates.add(
					cb.lessThan(root.get(field), value)
				);
			}
        }
        return finalPredicates;
	}
	
	
//	@SuppressWarnings("unchecked")
//	public List<Object> distinctFieldValues(DbField field) {
//		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
//		
//		Class<?> outputType = field.getType().getJavaClass();
//		if (field.getConnectedType() != null) {
//			outputType = field.getConnectedSchema().getPrimaryKey().getType().getJavaClass();
//		}
//		
//        CriteriaQuery query = cb.createQuery(outputType);
//        Root root = query.from(schema.getJavaClass());
//        
//        query.select(root.get(field.getJavaName()).as(outputType)).distinct(true);
//        
//        return entityManager.createQuery(query).getResultList();
//	}
}
