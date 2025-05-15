package business.order;

import api.ApiException;
import business.BookstoreDbException;
import business.JdbcUtils;
import business.book.Book;
import business.book.BookDao;
import business.cart.ShoppingCart;
import business.cart.ShoppingCartItem;
import business.customer.CustomerForm;
import business.customer.CustomerDao;
import business.order.OrderDao;
import business.order.LineItem;
import business.order.LineItemDao;
import business.customer.Customer;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.*;


public class DefaultOrderService implements OrderService {

	private BookDao bookDao;
	private OrderDao orderDao;
	private LineItemDao lineItemDao;
	private CustomerDao customerDao;

	public void setBookDao(BookDao bookDao) {
		this.bookDao = bookDao;
	}
	public void setOrderDao(OrderDao orderDao) {this.orderDao = orderDao; }
public void setLineItemDao(LineItemDao lineItemDao) { this.lineItemDao = lineItemDao;}
public void setCustomerDao(CustomerDao customerDao) { this.customerDao = customerDao;}

	@Override
	public OrderDetails getOrderDetails(long orderId) {
		Order order = orderDao.findByOrderId(orderId);
		Customer customer = customerDao.findByCustomerId(order.getCustomerId());
		List<LineItem> lineItems = lineItemDao.findByOrderId(orderId);
		List<Book> books = lineItems
				.stream()
				.map(lineItem -> bookDao.findByBookId(lineItem.getBookId()))
				.collect(Collectors.toList());
		return new OrderDetails(order, customer, lineItems, books);
	}

	@Override
	public long placeOrder(CustomerForm customerForm, ShoppingCart cart) {

		validateCustomer(customerForm);
		validateCart(cart);

		try (Connection connection = JdbcUtils.getConnection()) {
			Date date = getDate(
					customerForm.getCcExpiryMonth(),
					customerForm.getCcExpiryYear());
			return performPlaceOrderTransaction(
					customerForm.getName(),
					customerForm.getAddress(),
					customerForm.getPhone(),
					customerForm.getEmail(),
					customerForm.getCcNumber(),
					date, cart, connection);
		} catch (SQLException e) {
			throw new BookstoreDbException("Error during close connection for customer order", e);
		}

	}

	private Date getDate(String monthString, String yearString) {
		return new GregorianCalendar(Integer.parseInt(yearString), Integer.parseInt( monthString)-1, 1).getTime();
	}


	private long performPlaceOrderTransaction(
			String name, String address, String phone,
			String email, String ccNumber, Date date,
			ShoppingCart cart, Connection connection) {
		try {
			connection.setAutoCommit(false);
			long customerId = customerDao.create(
					connection, name, address, phone, email,
					ccNumber, date);
			long customerOrderId = orderDao.create(
					connection,
					cart.getComputedSubtotal() + cart.getSurcharge(),
					generateConfirmationNumber(), customerId);
			for (ShoppingCartItem item : cart.getItems()) {
				lineItemDao.create(connection, customerOrderId,
						item.getBookId(), item.getQuantity());
			}
			connection.commit();
			return customerOrderId;
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println(e);
			try {
				connection.rollback();
			} catch (SQLException e1) {
				throw new BookstoreDbException("Failed to roll back transaction", e1);
			}
			return 0;
		}
	}
	private void validateCustomer(CustomerForm customerForm) {

		String name = customerForm.getName();
		String address = customerForm.getAddress();
		String phone = customerForm.getPhone();
		String email = customerForm.getEmail();
		String ccNumber = customerForm.getCcNumber();
		String ccExpiryMonth = customerForm.getCcExpiryMonth();
		String ccExpiryYear = customerForm.getCcExpiryYear();

		if (name == null || name.equals("") || name.length() > 45) {
			throw new ApiException.ValidationFailure("Invalid name field");
		}

		// TODO: Validation checks for address, phone, email, ccNumber
		if (address == null || address.isEmpty() || address.length() < 4 || address.length() > 45) {
			throw new ApiException.ValidationFailure("address", "Invalid address field");
		}

		// Validate phone number
		if(phone == null || phone.isEmpty()) {
			throw new ApiException.ValidationFailure("phone", "Empty phone number");
		}
		String digitsOnly = phone.replaceAll("[\\s()-]", "");
		if (!digitsOnly.matches("^\\d{10}$")) {
			throw new ApiException.ValidationFailure("phone", "Invalid phone number");
		}

		// Validate email
		if (email == null || email.isEmpty() || !email.contains("@") || email.endsWith(".")) {
			throw new ApiException.ValidationFailure("email", "Invalid email address");
		}

		// Validate credit card number
		if(ccNumber == null || ccNumber.isEmpty()) {
			throw new ApiException.ValidationFailure("ccNumber", "Empty ccNumber");
		}
		String ccDigitsOnly = ccNumber.replaceAll("[^0-9]", "");
		if (ccDigitsOnly.length() < 14 || ccDigitsOnly.length() > 16) {
			throw new ApiException.ValidationFailure("ccNumber", "Invalid credit card number");
		}

		// Validate expiry date
		if(ccExpiryMonth == null || ccExpiryMonth.isEmpty()) {
			throw new ApiException.ValidationFailure("Please enter a valid expiration date.");
		}
		if(ccExpiryYear == null || ccExpiryYear.isEmpty()) {
			throw new ApiException.ValidationFailure("Please enter a valid expiration date.");
		}
		if (expiryDateIsInvalid(ccExpiryMonth, ccExpiryYear)) {
			throw new ApiException.ValidationFailure("Please enter a valid expiration date.");
		}
		if (expiryDateIsInvalid(customerForm.getCcExpiryMonth(), customerForm.getCcExpiryYear())) {
			throw new ApiException.ValidationFailure("Invalid expiry date");

		}
	}
	private int generateConfirmationNumber() {
		Random random = new Random();
		return random.nextInt(999999999);
	}
	private boolean expiryDateIsInvalid(String ccExpiryMonth, String ccExpiryYear) {

		// Get the current year and month
		YearMonth currentYearMonth = YearMonth.now();

		// Parse the credit card expiry year and month
		int expiryYear = Integer.parseInt(ccExpiryYear);
		int expiryMonth = Integer.parseInt(ccExpiryMonth);

		// Compare the credit card expiry year and month to the current year and month
		if (expiryYear < currentYearMonth.getYear() ||
				(expiryYear == currentYearMonth.getYear() && expiryMonth < currentYearMonth.getMonthValue()) ||
				!(1 <= expiryMonth && expiryMonth <= 12)) {
			return true; // The expiry date is before the current date
		} else {
			return false; // The expiry date is after or equal to the current date
		}
	}

	private void validateCart(ShoppingCart cart) {

		if (cart.getItems().size() <= 0) {
			throw new ApiException.ValidationFailure("Cart is empty.");
		}

		cart.getItems().forEach(item-> {
			if (item.getQuantity() <= 0 || item.getQuantity() > 99) {
				throw new ApiException.ValidationFailure("Invalid quantity");
			}
			Book databaseBook = bookDao.findByBookId(item.getBookId());
			// TODO: complete the required validations
			if (databaseBook == null) {
				throw new ApiException.ValidationFailure("Book with ID " + item.getBookId() + " not found in the database.");
			}
			if (item.getBookForm().getPrice() != databaseBook.price()) {
				throw new ApiException.ValidationFailure("Invalid price for book");
			}
			if (item.getBookForm().getCategoryId() != databaseBook.categoryId()) {
				throw new ApiException.ValidationFailure("Invalid category for book");
			}
		});
	}

}